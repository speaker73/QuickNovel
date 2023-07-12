package com.lagradost.quicknovel

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.speech.tts.Voice
import androidx.annotation.WorkerThread
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.request.target.Target
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.quicknovel.BaseApplication.Companion.context
import com.lagradost.quicknovel.BaseApplication.Companion.getKey
import com.lagradost.quicknovel.BaseApplication.Companion.getKeyClass
import com.lagradost.quicknovel.BaseApplication.Companion.removeKey
import com.lagradost.quicknovel.BaseApplication.Companion.setKey
import com.lagradost.quicknovel.BaseApplication.Companion.setKeyClass
import com.lagradost.quicknovel.BookDownloader2Helper.getQuickChapter
import com.lagradost.quicknovel.CommonActivity.showToast
import com.lagradost.quicknovel.TTSHelper.parseTextToSpans
import com.lagradost.quicknovel.TTSHelper.preParseHtml
import com.lagradost.quicknovel.TTSHelper.render
import com.lagradost.quicknovel.TTSHelper.ttsParseText
import com.lagradost.quicknovel.mvvm.Resource
import com.lagradost.quicknovel.mvvm.letInner
import com.lagradost.quicknovel.mvvm.logError
import com.lagradost.quicknovel.mvvm.map
import com.lagradost.quicknovel.mvvm.safeApiCall
import com.lagradost.quicknovel.providers.RedditProvider
import com.lagradost.quicknovel.ui.OrientationType
import com.lagradost.quicknovel.ui.ScrollIndex
import com.lagradost.quicknovel.ui.ScrollVisibilityIndex
import com.lagradost.quicknovel.ui.TextConfig
import com.lagradost.quicknovel.ui.toScroll
import com.lagradost.quicknovel.util.Apis
import com.lagradost.quicknovel.util.Coroutines.ioSafe
import com.lagradost.quicknovel.util.Coroutines.runOnMainThread
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.SoftBreakAddsNewLinePlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.image.AsyncDrawable
import io.noties.markwon.image.ImageSizeResolver
import io.noties.markwon.image.glide.GlideImagesPlugin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nl.siegmann.epublib.domain.Book
import nl.siegmann.epublib.epub.EpubReader
import org.jsoup.Jsoup
import java.lang.ref.WeakReference
import java.net.URLDecoder
import java.util.Locale
import kotlin.reflect.KClass
import kotlin.reflect.KProperty

const val DEF_FONT_SIZE: Int = 14

class PreferenceDelegate<T : Any>(
    val key: String, val default: T, private val klass: KClass<T>
) {
    // simple cache to make it not get the key every time it is accessed, however this requires
    // that ONLY this changes the key
    private var cache: T? = null

    operator fun getValue(self: Any?, property: KProperty<*>) =
        cache ?: getKeyClass(key, klass.java).also { newCache -> cache = newCache } ?: default

    operator fun setValue(
        self: Any?,
        property: KProperty<*>,
        t: T?
    ) {
        cache = t
        if (t == null) {
            removeKey(key)
        } else {
            setKeyClass(key, t)
        }
    }
}

class PreferenceDelegateLiveView<T : Any>(
    val key: String, val default: T, klass: KClass<T>, private val _liveData: MutableLiveData<T>
) {
    // simple cache to make it not get the key every time it is accessed, however this requires
    // that ONLY this changes the key
    private var cache: T

    init {
        cache = getKeyClass(key, klass.java) ?: default
        _liveData.postValue(cache)
    }

    operator fun getValue(self: Any?, property: KProperty<*>) = cache

    operator fun setValue(
        self: Any?,
        property: KProperty<*>,
        t: T?
    ) {
        cache = t ?: default
        _liveData.postValue(cache)
        if (t == null) {
            removeKey(key)
        } else {
            setKeyClass(key, t)
        }
    }
}

abstract class AbstractBook {
    open fun resolveUrl(url: String): String {
        return url
    }

    abstract val canReload: Boolean

    abstract fun size(): Int
    abstract fun title(): String
    abstract fun getChapterTitle(index: Int): String
    abstract fun getLoadingStatus(index: Int): String?

    @WorkerThread
    @Throws
    abstract suspend fun getChapterData(index: Int, reload: Boolean): String

    abstract fun expand(last: String): Boolean

    @WorkerThread
    @Throws
    protected abstract suspend fun posterBytes(): ByteArray?

    private var poster: Bitmap? = null

    init {
        ioSafe {
            poster = posterBytes()?.let { byteArray ->
                BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size)
            }
        }
    }

    fun poster(): Bitmap? {
        return poster
    }
}

class QuickBook(val data: QuickStreamData) : AbstractBook() {
    override fun resolveUrl(url: String): String {
        return Apis.getApiFromNameNull(data.meta.apiName)?.fixUrl(url) ?: url
    }

    override val canReload = true

    override fun size(): Int {
        return data.data.size
    }

    override fun title(): String {
        return data.meta.name
    }

    override fun getChapterTitle(index: Int): String {
        return data.data[index].name
    }

    override fun getLoadingStatus(index: Int): String {
        return data.data[index].url
    }

    override suspend fun getChapterData(index: Int, reload: Boolean): String {
        val ctx = context ?: throw ErrorLoadingException("Invalid context")
        return ctx.getQuickChapter(
            data.meta,
            data.data[index],
            index,
            reload
        )?.html ?: throw ErrorLoadingException("Error loading chapter")
    }

    override fun expand(last: String): Boolean {
        try {
            val elements =
                Jsoup.parse(last).allElements.filterNotNull()

            for (element in elements) {
                val href = element.attr("href") ?: continue

                val text =
                    element.ownText().replace(Regex("[\\[\\]().,|{}<>]"), "").trim()
                if (text.equals("next", true) || text.equals(
                        "next chapter",
                        true
                    ) || text.equals("next part", true)
                ) {
                    val name = RedditProvider.getName(href) ?: "Next"
                    data.data.add(ChapterData(name, href, null, null))
                    return true
                }
            }
        } catch (e: Exception) {
            logError(e)
        }
        return false
    }

    override suspend fun posterBytes(): ByteArray? {
        val poster = data.poster
        if (poster != null) {
            try {
                return MainActivity.app.get(poster).okhttpResponse.body.bytes()
            } catch (t: Throwable) {
                logError(t)
            }
        }
        return null
    }
}

class RegularBook(val data: Book) : AbstractBook() {
    override val canReload = false

    override fun size(): Int {
        return data.tableOfContents.tocReferences.size
    }

    override fun title(): String {
        return data.title
    }

    override fun getChapterTitle(index: Int): String {
        return data.tableOfContents.tocReferences?.get(index)?.title ?: "Chapter ${index + 1}"
    }

    override fun getLoadingStatus(index: Int): String? {
        return null
    }

    override suspend fun getChapterData(index: Int, reload: Boolean): String {
        return data.tableOfContents.tocReferences[index].resource.reader.readText()
    }

    override fun expand(last: String): Boolean {
        return false
    }

    override suspend fun posterBytes(): ByteArray? {
        return data.coverImage?.data
    }
}

data class LiveChapterData(
    val spans: List<TextSpan>,
    val title: String,
    val rawText: String,
    val ttsLines: List<TTSHelper.TTSLine>
)

data class ChapterUpdate(
    val data: ArrayList<SpanDisplay>,
    val seekToDesired: Boolean
)

class ReadActivityViewModel : ViewModel() {
    private lateinit var book: AbstractBook
    private lateinit var markwon: Markwon

    fun canReload(): Boolean {
        return book.canReload
    }

    private var _context: WeakReference<ReadActivity2>? = null
    var context
        get() = _context?.get()
        private set(value) {
            _context = WeakReference(value)
        }


    private val _chapterData: MutableLiveData<ChapterUpdate> =
        MutableLiveData<ChapterUpdate>(null)
    val chapter: LiveData<ChapterUpdate> = _chapterData

    // we use bool as we cant construct Nothing, does not represent anything
    private val _loadingStatus: MutableLiveData<Resource<Boolean>> =
        MutableLiveData<Resource<Boolean>>(null)
    val loadingStatus: LiveData<Resource<Boolean>> = _loadingStatus

    private val _chaptersTitles: MutableLiveData<List<String>> =
        MutableLiveData<List<String>>(null)
    val chaptersTitles: LiveData<List<String>> = _chaptersTitles

    private val _title: MutableLiveData<String> =
        MutableLiveData<String>(null)
    val title: LiveData<String> = _title

    private val _chapterTile: MutableLiveData<String> =
        MutableLiveData<String>(null)
    val chapterTile: LiveData<String> = _chapterTile

    private val _bottomVisibility: MutableLiveData<Boolean> =
        MutableLiveData<Boolean>(false)
    val bottomVisibility: LiveData<Boolean> = _bottomVisibility

    private val _ttsStatus: MutableLiveData<TTSHelper.TTSStatus> =
        MutableLiveData<TTSHelper.TTSStatus>(TTSHelper.TTSStatus.IsStopped)
    val ttsStatus: LiveData<TTSHelper.TTSStatus> = _ttsStatus

    private val _ttsLine: MutableLiveData<TTSHelper.TTSLine?> =
        MutableLiveData<TTSHelper.TTSLine?>(null)
    val ttsLine: LiveData<TTSHelper.TTSLine?> = _ttsLine

    /*  private val _orientation: MutableLiveData<OrientationType> =
          MutableLiveData<OrientationType>(null)
      val orientation: LiveData<OrientationType> = _orientation

      private val _backgroundColor: MutableLiveData<Int> =
          MutableLiveData<Int>(null)
      val backgroundColor: LiveData<Int> = _backgroundColor

      private val _textColor: MutableLiveData<Int> =
          MutableLiveData<Int>(null)
      val textColor: LiveData<Int> = _textColor

      private val _textSize: MutableLiveData<Int> =
          MutableLiveData<Int>(null)
      val textSize: LiveData<Int> = _textSize*/

    // private val _textFont: MutableLiveData<String> =
    //     MutableLiveData<String>(null)
    // val textFont: LiveData<String> = _textFont

    fun switchVisibility() {
        _bottomVisibility.postValue(!(_bottomVisibility.value ?: false))
    }


    private var chaptersTitlesInternal: ArrayList<String> = arrayListOf()

    var desiredIndex: ScrollIndex? = null
    var desiredTTSIndex: ScrollIndex? = null

    private fun updateChapters() {
        for (idx in chaptersTitlesInternal.size until book.size()) {
            chaptersTitlesInternal.add(book.getChapterTitle(idx))
        }
        _chaptersTitles.postValue(chaptersTitlesInternal)
    }

    private val chapterMutex = Mutex()
    private val chapterExpandMutex = Mutex()

    private val requested: HashSet<Int> = hashSetOf()

    private val loading: HashSet<Int> = hashSetOf()
    private val chapterData: HashMap<Int, Resource<LiveChapterData>?> = hashMapOf()
    private val hasExpanded: HashSet<Int> = hashSetOf()

    private var currentIndex = Int.MIN_VALUE

    /** lower padding for preloading current-chapterPaddingBottom*/
    private var chapterPaddingBottom: Int = 1

    /** upper padding, for preloading current+chapterPaddingTop */
    private var chapterPaddingTop: Int = 2

    fun reloadChapter(index: Int) = ioSafe {
        loadIndividualChapter(index, reload = true, notify = false)
        updateReadArea(seekToDesired = false)
    }

    fun reloadChapter() {
        reloadChapter(currentIndex)
    }

    private suspend fun updateIndexAsync(
        index: Int,
        notify: Boolean = true
    ) {
        for (idx in index - chapterPaddingBottom..index + chapterPaddingTop) {
            requested += index
            loadIndividualChapter(idx, false, notify)
        }
    }

    private fun updateIndex(index: Int) {
        var alreadyRequested = false
        for (idx in index - chapterPaddingBottom..index + chapterPaddingTop) {
            if (!requested.contains(index)) {
                alreadyRequested = true
            }
            requested += index
        }

        if (alreadyRequested) return

        ioSafe {
            updateIndexAsync(index)
        }
    }

    private fun updateReadArea(seekToDesired: Boolean = false) {
        val cIndex = currentIndex
        val chapters = ArrayList<SpanDisplay>()
        for (idx in cIndex - chapterPaddingBottom..cIndex + chapterPaddingTop) {
            val append: List<SpanDisplay> = when (val data = chapterData[idx]) {
                null -> emptyList()
                is Resource.Loading -> {
                    listOf<SpanDisplay>(LoadingSpanned(data.url, idx))
                }

                is Resource.Success -> {
                    data.value.spans
                }

                is Resource.Failure -> listOf<SpanDisplay>(
                    FailedSpanned(
                        reason = data.errorString,
                        index = idx,
                        canReload = data.isNetworkError
                    )
                )
            }

            if (idx < chaptersTitlesInternal.size && idx >= 0)
                chapters.add(ChapterStartSpanned(idx, 0, chaptersTitlesInternal[idx]))
            chapters.addAll(append)
        }
        _chapterData.postValue(ChapterUpdate(data = chapters, seekToDesired = seekToDesired))
    }

    private fun notifyChapterUpdate(index: Int, seekToDesired: Boolean = false) {
        val cIndex = currentIndex
        if (cIndex - chapterPaddingBottom <= index && index <= cIndex + chapterPaddingTop) {
            updateReadArea(seekToDesired)
        }
    }

    private val markwonMutex = Mutex()

    @WorkerThread
    private suspend fun loadIndividualChapter(
        index: Int,
        reload: Boolean = false,
        notify: Boolean = true
    ) {
        if (index < 0) return

        // set loading and return early if already loading or return cache
        chapterMutex.withLock {
            if (loading.contains(index)) return
            if (!reload) {
                if (chapterData.contains(index)) {
                    return
                }
            }

            loading += index
            chapterData[index] = Resource.Loading(null)
            if (notify) notifyChapterUpdate(index)
        }

        // we check for out of bounds and if it is out of bounds then try to expand it (Reddit next)
        // we lock it here to prevent duplicate loading when init
        chapterExpandMutex.withLock {
            val preSize = book.size()
            while (index >= book.size()) {
                // will only expand once per session per chapter
                if (hasExpanded.contains(book.size())) break
                hasExpanded += book.size()

                try {
                    // we assume that the text is cached
                    book.expand(book.getChapterData(book.size() - 1, reload = false))
                } catch (t: Throwable) {
                    logError(t)
                }
            }
            if (preSize != book.size()) updateChapters()
        }

        // if we are still out of bounds then return no more chapters
        if (index >= book.size()) {
            chapterMutex.withLock {
                // only push one no more chapters
                if (index == book.size()) {
                    chapterData[index] =
                        Resource.Failure(
                            false,
                            null,
                            null,
                            context?.getString(R.string.no_more_chapters) ?: "ERROR"
                        )
                } else {
                    chapterData[index] = null
                }
                loading -= index
                if (notify) notifyChapterUpdate(index)
            }
            return
        }

        // we have verified we are within bounds, then set the loading to the index url
        chapterMutex.withLock {
            chapterData[index] = Resource.Loading(book.getLoadingStatus(index))
            if (notify) notifyChapterUpdate(index)
        }

        // load the data and precalculate everything needed
        val data = safeApiCall {
            book.getChapterData(index, reload)
        }.map { text ->
            val rawText = preParseHtml(text)

            val render = markwonMutex.withLock { render(rawText, markwon) }

            LiveChapterData(
                rawText = rawText,
                spans = parseTextToSpans(render, index),
                title = book.getChapterTitle(index),
                ttsLines = ttsParseText(render.substring(0, render.length), index)
            )
        }

        // set the data and return
        chapterMutex.withLock {
            chapterData[index] = data
            loading -= index
            if (notify) notifyChapterUpdate(index)
        }
    }

    fun init(intent: Intent?, context: ReadActivity2) = ioSafe {
        _loadingStatus.postValue(Resource.Loading())
        this@ReadActivityViewModel.context = context
        initTTSSession(context)

        val loadedBook = safeApiCall {
            if (intent == null) throw ErrorLoadingException("No intent")

            val data = intent.data ?: throw ErrorLoadingException("Empty intent")
            val input = context.contentResolver.openInputStream(data)
                ?: throw ErrorLoadingException("Empty data")
            val isFromEpub = intent.type == "application/epub+zip"

            val epub = if (isFromEpub) {
                val epubReader = EpubReader()
                val book = epubReader.readEpub(input)
                RegularBook(book)
            } else {
                QuickBook(DataStore.mapper.readValue<QuickStreamData>(input.reader().readText()))
            }

            if (epub.size() <= 0) {
                throw ErrorLoadingException("Empty book, failed to parse ${intent.type}")
            }
            epub
        }

        when (loadedBook) {
            is Resource.Success -> {
                init(loadedBook.value, context)

                // cant assume we know a chapter max as it can expand
                val loadedChapter =
                    maxOf(getKey(EPUB_CURRENT_POSITION, book.title()) ?: 0, 0)


                // we the current loaded thing here, but because loadedChapter can be >= book.size (expand) we have to check
                if (loadedChapter < book.size()) {
                    _loadingStatus.postValue(Resource.Loading(book.getLoadingStatus(loadedChapter)))
                }

                currentIndex = loadedChapter
                updateIndexAsync(loadedChapter, notify = false)

                val char = getKey(
                    EPUB_CURRENT_POSITION_SCROLL_CHAR, book.title()
                ) ?: 0

                val innerIndex = innerCharToIndex(currentIndex, char) ?: 0

                // don't update as you want to seek on update
                changeIndex(ScrollIndex(currentIndex, innerIndex, char))

                // notify once because initial load is 3 chapters I don't care about 10 notifications when the user cant see it
                updateReadArea(seekToDesired = true)

                _loadingStatus.postValue(
                    Resource.Success(true)
                )
            }

            is Resource.Failure -> {
                _loadingStatus.postValue(
                    Resource.Failure(
                        loadedBook.isNetworkError,
                        loadedBook.errorCode,
                        loadedBook.errorResponse,
                        loadedBook.errorString
                    )
                )
            }

            else -> throw NotImplementedError()
        }
    }

    fun init(book: AbstractBook, context: Context) {
        this.book = book
        _title.postValue(book.title())

        updateChapters()

        markwon = Markwon.builder(context) // automatically create Glide instance
            //.usePlugin(GlideImagesPlugin.create(context)) // use supplied Glide instance
            //.usePlugin(GlideImagesPlugin.create(Glide.with(context))) // if you need more control
            .usePlugin(HtmlPlugin.create { plugin -> plugin.excludeDefaults(false) })
            .usePlugin(GlideImagesPlugin.create(object : GlideImagesPlugin.GlideStore {
                override fun load(drawable: AsyncDrawable): RequestBuilder<Drawable> {
                    return try {
                        val newUrl = drawable.destination.substringAfter("&url=")
                        val url =
                            book.resolveUrl(
                                if (newUrl.length > 8) { // we assume that it is not a stub url by length > 8
                                    URLDecoder.decode(newUrl)
                                } else {
                                    drawable.destination
                                }
                            )

                        Glide.with(context)
                            .load(GlideUrl(url) { mapOf("user-agent" to USER_AGENT) })
                    } catch (e: Exception) {
                        logError(e)
                        Glide.with(context)
                            .load(R.drawable.books_emoji) // might crash :)
                    }
                }

                override fun cancel(target: Target<*>) {
                    try {
                        Glide.with(context).clear(target)
                    } catch (e: Exception) {
                        logError(e)
                    }
                }
            }))
            .usePlugin(object :
                AbstractMarkwonPlugin() {
                override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                    builder.imageSizeResolver(object : ImageSizeResolver() {
                        override fun resolveImageSize(drawable: AsyncDrawable): Rect {
                            return drawable.result.bounds
                        }
                    })
                }
            })
            .usePlugin(SoftBreakAddsNewLinePlugin.create())
            .build()
    }

    // ========================================  TTS STUFF ========================================

    lateinit var ttsSession: TTSSession

    private fun initTTSSession(context: Context) {
        runOnMainThread {
            ttsSession = TTSSession(context, ::parseAction)
        }
    }

    private var pendingTTSSkip: Int = 0
    private var _currentTTSStatus: TTSHelper.TTSStatus = TTSHelper.TTSStatus.IsStopped
    var currentTTSStatus: TTSHelper.TTSStatus
        get() = _currentTTSStatus
        set(value) {
            if (_currentTTSStatus == TTSHelper.TTSStatus.IsStopped && value == TTSHelper.TTSStatus.IsRunning) {
                startTTSThread()
            }

            _ttsStatus.postValue(value)
            _currentTTSStatus = value
        }

    fun stopTTS() {
        if (!ttsSession.ttsInitalized()) return
        currentTTSStatus = TTSHelper.TTSStatus.IsStopped
    }

    fun setTTSLanguage(locale: Locale?) {
        ttsSession.setLanguage(locale)
    }

    fun setTTSVoice(voice: Voice?) {
        ttsSession.setVoice(voice)
    }

    fun pauseTTS() {
        if (!ttsSession.ttsInitalized()) return
        currentTTSStatus = TTSHelper.TTSStatus.IsPaused
    }

    fun startTTS() {
        currentTTSStatus = TTSHelper.TTSStatus.IsRunning
    }

    fun forwardsTTS() {
        if (!ttsSession.ttsInitalized()) return
        pendingTTSSkip += 1
    }

    fun backwardsTTS() {
        if (!ttsSession.ttsInitalized()) return
        pendingTTSSkip -= 1
    }

    fun pausePlayTTS() {
        if (currentTTSStatus == TTSHelper.TTSStatus.IsRunning) {
            currentTTSStatus = TTSHelper.TTSStatus.IsPaused
        } else if (currentTTSStatus == TTSHelper.TTSStatus.IsPaused) {
            currentTTSStatus = TTSHelper.TTSStatus.IsRunning
        }
    }

    fun isTTSRunning(): Boolean {
        return currentTTSStatus == TTSHelper.TTSStatus.IsRunning
    }


    private val ttsThreadMutex = Mutex()
    private fun startTTSThread() = ioSafe {
        val dIndex = desiredTTSIndex ?: desiredIndex ?: return@ioSafe

        if (ttsThreadMutex.isLocked) return@ioSafe
        ttsThreadMutex.withLock {
            ttsSession.register()

            var innerIndex = 0
            var index = dIndex.index

            let {
                val startChar = dIndex.char

                val lines = chapterMutex.withLock {
                    chapterData[index].letInner {
                        it.ttsLines
                    }
                } ?: run {
                    // in case of error just go to the next chapter
                    index++
                    return@let
                }

                val idx = lines.indexOfFirst { it.startChar >= startChar }
                if (idx != -1)
                    innerIndex = idx
            }
            while (true) {
                if (currentTTSStatus == TTSHelper.TTSStatus.IsStopped) break
                val lines = when (val currentData = chapterMutex.withLock { chapterData[index] }) {
                    null -> {
                        showToast("Got null data")
                        break
                    }

                    is Resource.Failure -> {
                        showToast(currentData.errorString)
                        break
                    }

                    is Resource.Loading -> {
                        if (currentTTSStatus == TTSHelper.TTSStatus.IsStopped) break
                        delay(100)
                        continue
                    }

                    is Resource.Success -> {
                        currentData.value.ttsLines
                    }
                }

                fun notify() {
                    TTSNotifications.notify(
                        book.title(),
                        chaptersTitlesInternal[index],
                        book.poster(),
                        currentTTSStatus,
                        context
                    )
                }
                notify()

                // this is because if you go back one line you will be on the previous chapter with
                // a negative innerIndex, this makes the wrapping good
                if (innerIndex < 0) {
                    innerIndex += lines.size
                }

                updateIndex(index)

                // speak all lines
                while (innerIndex < lines.size && innerIndex >= 0) {
                    if (currentTTSStatus == TTSHelper.TTSStatus.IsStopped) break

                    val line = lines[innerIndex]
                    val nextLine = lines.getOrNull(innerIndex + 1)

                    // set keys
                    setKey(
                        EPUB_CURRENT_POSITION_SCROLL_CHAR,
                        book.title(),
                        line.startChar
                    )
                    setKey(EPUB_CURRENT_POSITION, book.title(), line.index)

                    // post visual
                    _ttsLine.postValue(line)

                    // wait for next line
                    val waitFor = ttsSession.speak(line, nextLine)
                    ttsSession.waitForOr(waitFor, {
                        currentTTSStatus != TTSHelper.TTSStatus.IsRunning || pendingTTSSkip != 0
                    }) {
                        notify()
                    }

                    // wait for pause
                    var isPauseDuration = 0
                    while (currentTTSStatus == TTSHelper.TTSStatus.IsPaused) {
                        isPauseDuration++
                        delay(100)
                    }

                    // if we pause then we resume on the same line
                    if (isPauseDuration > 0) {
                        notify()
                        pendingTTSSkip = 0
                        continue
                    }

                    if (pendingTTSSkip != 0) {
                        innerIndex += pendingTTSSkip
                        pendingTTSSkip = 0
                    } else {
                        innerIndex += 1
                    }
                }
                if (currentTTSStatus == TTSHelper.TTSStatus.IsStopped) break

                if (innerIndex > 0) {
                    // goto next chapter and set inner to 0
                    index++
                    innerIndex = 0
                } else if (index > 0) {
                    index--
                } else {
                    innerIndex = 0
                }
            }

            TTSNotifications.notify(
                book.title(),
                "",
                book.poster(),
                TTSHelper.TTSStatus.IsStopped,
                context
            )
            ttsSession.unregister()
            _ttsLine.postValue(null)
        }
    }

    fun parseAction(input: TTSHelper.TTSActionType): Boolean {

        // validate that the action makes sense
        if (
            (currentTTSStatus == TTSHelper.TTSStatus.IsPaused && input == TTSHelper.TTSActionType.Pause) ||
            (currentTTSStatus != TTSHelper.TTSStatus.IsPaused && input == TTSHelper.TTSActionType.Resume) ||
            (currentTTSStatus == TTSHelper.TTSStatus.IsStopped && input == TTSHelper.TTSActionType.Stop) ||
            (currentTTSStatus != TTSHelper.TTSStatus.IsRunning && input == TTSHelper.TTSActionType.Next)
        ) {
            return false
        }

        if (!ttsSession.ttsInitalized()) return false

        when (input) {
            TTSHelper.TTSActionType.Pause -> pauseTTS()
            TTSHelper.TTSActionType.Resume -> startTTS()
            TTSHelper.TTSActionType.Stop -> stopTTS()
            TTSHelper.TTSActionType.Next -> forwardsTTS()
        }

        return true
    }

    fun innerCharToIndex(index: Int, char: Int): Int? {
        // the lock is so short it does not matter I *hope*
        return runBlocking {
            chapterMutex.withLock { chapterData[index] }?.letInner { live ->
                live.spans.firstOrNull { it.start >= char }?.innerIndex
            }
        }
    }

    /** sets the metadata and global vars used as well as keys */
    private fun changeIndex(scrollIndex: ScrollIndex) {
        _chapterTile.postValue(chaptersTitlesInternal[scrollIndex.index])

        desiredIndex = scrollIndex
        currentIndex = scrollIndex.index

        setKey(
            EPUB_CURRENT_POSITION_SCROLL_CHAR,
            book.title(),
            scrollIndex.char
        )
        setKey(EPUB_CURRENT_POSITION, book.title(), scrollIndex.index)
    }

    fun scrollToDesired(scrollIndex: ScrollIndex) {
        changeIndex(scrollIndex)
        updateReadArea(seekToDesired = true)
    }

    fun seekToChapter(index: Int) = ioSafe {
        // sanity check
        if (index < 0 || index >= book.size()) return@ioSafe

        // we wont allow chapter switching and tts at the same time, stop it
        if (currentTTSStatus != TTSHelper.TTSStatus.IsStopped) {
            currentTTSStatus = TTSHelper.TTSStatus.IsStopped
        }

        // set loading
        _loadingStatus.postValue(Resource.Loading())

        // load the chapters
        updateIndexAsync(index, notify = false)

        // set the keys
        setKey(EPUB_CURRENT_POSITION, book.title(), index)
        setKey(EPUB_CURRENT_POSITION_SCROLL_CHAR, book.title(), 0)

        // set the state
        desiredIndex = ScrollIndex(index, 0, 0)
        currentIndex = index
        desiredTTSIndex = ScrollIndex(index, 0, 0)

        // push the update
        updateReadArea(seekToDesired = true)

        // update the view
        _chapterTile.postValue(chaptersTitlesInternal[index])
        _loadingStatus.postValue(Resource.Success(true))
    }

    /*private fun changeIndex(index: Int, updateArea: Boolean = true) {
        val realNewIndex = minOf(index, book.size() - 1)
        if (currentIndex == realNewIndex) return
        setKey(EPUB_CURRENT_POSITION, book.title(), realNewIndex)
        currentIndex = realNewIndex
        if (updateArea) updateReadArea()
        _chapterTile.postValue(chaptersTitlesInternal[realNewIndex])
    }*/

    fun onScroll(visibility: ScrollVisibilityIndex?) {
        if (visibility == null) return

        // dynamically increase padding in case of very small chapters with a maximum of 10 chapters
        val first = visibility.firstInMemory.index
        val last = visibility.lastInMemory.index
        chapterPaddingTop = minOf(10, maxOf(chapterPaddingTop, (last - first) + 1))

        val current = currentIndex

        val save = visibility.firstFullyVisible ?: visibility.firstInMemory
        desiredTTSIndex = visibility.firstFullyVisibleUnderLine?.toScroll()
        changeIndex(save.toScroll())

        // update the read area if changed index
        if (current != save.index)
            updateReadArea()

        // load forwards and backwards
        updateIndex(visibility.firstInMemory.index)
        updateIndex(visibility.lastInMemory.index)
    }

    override fun onCleared() {
        ttsSession.release()

        super.onCleared()
    }


    var scrollWithVolume by PreferenceDelegate(EPUB_SCROLL_VOL, true, Boolean::class)
    var ttsLock by PreferenceDelegate(EPUB_TTS_LOCK, true, Boolean::class)
    val textFontLive: MutableLiveData<String> = MutableLiveData(null)
    var textFont by PreferenceDelegateLiveView(EPUB_FONT, "", String::class, textFontLive)
    val textSizeLive: MutableLiveData<Int> = MutableLiveData(null)
    var textSize by PreferenceDelegateLiveView(
        EPUB_TEXT_SIZE,
        DEF_FONT_SIZE,
        Int::class,
        textSizeLive
    )

    val orientationLive: MutableLiveData<Int> = MutableLiveData(null)
    var orientation by PreferenceDelegateLiveView(
        EPUB_LOCK_ROTATION,
        OrientationType.DEFAULT.prefValue,
        Int::class, orientationLive
    )

    val textColorLive: MutableLiveData<Int> = MutableLiveData(null)
    var textColor by PreferenceDelegateLiveView(
        EPUB_TEXT_COLOR, ContextCompat.getColor(
            BaseApplication.context!!,
            R.color.readerTextColor
        ), Int::class, textColorLive
    )

    val backgroundColorLive: MutableLiveData<Int> = MutableLiveData(null)
    var backgroundColor by PreferenceDelegateLiveView(
        EPUB_BG_COLOR, ContextCompat.getColor(
            BaseApplication.context!!,
            R.color.readerBackground
        ), Int::class, backgroundColorLive
    )

    val showBatteryLive: MutableLiveData<Boolean> = MutableLiveData(null)
    var showBattery by PreferenceDelegateLiveView(
        EPUB_HAS_BATTERY, true, Boolean::class, showBatteryLive
    )

    val showTimeLive: MutableLiveData<Boolean> = MutableLiveData(null)
    var showTime by PreferenceDelegateLiveView(
        EPUB_HAS_TIME, true, Boolean::class, showTimeLive
    )

    //val time12HLive: MutableLiveData<Boolean> = MutableLiveData(null)
    //var time12H by PreferenceDelegateLiveView(
    //    EPUB_TWELVE_HOUR_TIME, false, Boolean::class, time12HLive
    //)

    val screenAwakeLive: MutableLiveData<Boolean> = MutableLiveData(null)
    var screenAwake by PreferenceDelegateLiveView(
        EPUB_KEEP_SCREEN_ACTIVE, true, Boolean::class, screenAwakeLive
    )

}