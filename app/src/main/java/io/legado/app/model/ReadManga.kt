package io.legado.app.model

import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.ReadRecord
import io.legado.app.help.AppWebDav
import io.legado.app.help.ConcurrentRateLimiter
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.readSimulating
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.help.book.update
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.globalExecutor
import io.legado.app.model.webBook.WebBook
import io.legado.app.ui.book.manga.entities.MangaContent
import io.legado.app.ui.book.manga.entities.ReaderLoading
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.mapIndexed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlin.math.min

@Suppress("MemberVisibilityCanBePrivate")
object ReadManga : CoroutineScope by MainScope() {
    var inBookshelf = false
    var tocChanged = false
    var chapterChanged = false
    var book: Book? = null
    val executor = globalExecutor
    var durChapterIndex = 0 //章节位置
    var chapterSize = 0//总章节
    var durChapterImageCount = 0
    var durChapterPos = 0
    var bookSource: BookSource? = null
    var chapterTitle: String = ""
    var readStartTime: Long = System.currentTimeMillis()
    private val readRecord = ReadRecord()
    private val loadingChapters = arrayListOf<Int>()
    var simulatedChapterSize = 0
    var mCallback: Callback? = null
    var gameOver = false
    var preDownloadTask: Job? = null
    val downloadedChapters = hashSetOf<Int>()
    val downloadFailChapters = hashMapOf<Int, Int>()
    val downloadScope = CoroutineScope(SupervisorJob() + IO)
    var rateLimiter = ConcurrentRateLimiter(null)

    fun saveRead(pageChanged: Boolean = false) {
        executor.execute {
            val book = book ?: return@execute
            book.lastCheckCount = 0
            book.durChapterTime = System.currentTimeMillis()
            val changed = book.durChapterIndex != durChapterIndex
            book.durChapterIndex = durChapterIndex
            book.durChapterPos = durChapterPos
            if (!pageChanged || changed) {
                appDb.bookChapterDao.getChapter(book.bookUrl, durChapterIndex)?.let {
                    book.durChapterTitle = it.getDisplayTitle(
                        ContentProcessor.get(book.name, book.origin).getTitleReplaceRules(),
                        book.getUseReplaceRule()
                    )
                }
            }
            appDb.bookDao.update(book)
        }
    }

    fun upData(book: Book) {
        ReadManga.book = book
        chapterSize = appDb.bookChapterDao.getChapterCount(book.bookUrl)
        simulatedChapterSize = if (book.readSimulating()) {
            book.simulatedTotalChapterNum()
        } else {
            chapterSize
        }

        if (durChapterIndex != book.durChapterIndex || tocChanged) {
            durChapterIndex = book.durChapterIndex
            durChapterPos = book.durChapterPos
        }
        upWebBook(book)
        synchronized(this) {
            loadingChapters.clear()
        }
    }

    fun resetData(book: Book) {
        ReadManga.book = book
        readRecord.bookName = book.name
        readRecord.readTime = appDb.readRecordDao.getReadTime(book.name) ?: 0
        chapterSize = appDb.bookChapterDao.getChapterCount(book.bookUrl)
        simulatedChapterSize = if (book.readSimulating()) {
            book.simulatedTotalChapterNum()
        } else {
            chapterSize
        }
        durChapterIndex = book.durChapterIndex
        durChapterPos = book.durChapterPos
        upWebBook(book)
        synchronized(this) {
            loadingChapters.clear()
        }
    }

    fun upWebBook(book: Book) {
        appDb.bookSourceDao.getBookSource(book.origin)?.let {
            bookSource = it
            rateLimiter = ConcurrentRateLimiter(it)
        } ?: let {
            bookSource = null
        }
    }


    //每次切换章节更新阅读记录
    fun upReadTime() {
        executor.execute {
            if (!AppConfig.enableReadRecord) {
                return@execute
            }
            readRecord.readTime = readRecord.readTime + System.currentTimeMillis() - readStartTime
            readStartTime = System.currentTimeMillis()
            readRecord.lastRead = System.currentTimeMillis()
            appDb.readRecordDao.insert(readRecord)
        }
    }

    private fun addLoading(index: Int): Boolean {
        synchronized(this) {
            if (loadingChapters.contains(index)) return false
            loadingChapters.add(index)
            return true
        }
    }

    fun removeLoading(index: Int) {
        synchronized(this) {
            loadingChapters.remove(index)
        }
    }

    fun loading(index: Int): Boolean {
        synchronized(this) {
            return loadingChapters.contains(index)
        }
    }

    /**
     * 获取正文
     */
    private fun download(
        scope: CoroutineScope,
        chapter: BookChapter,
    ) {
        val book = book ?: return removeLoading(chapter.index)
        val bookSource = bookSource
        if (bookSource != null) {
            downloadNetworkContent(bookSource, scope, chapter, book, success = {
                downloadedChapters.add(chapter.index)
                downloadFailChapters.remove(chapter.index)
                contentLoadFinish(chapter, it, book)
            }, error = {
                downloadFailChapters[chapter.index] =
                    (downloadFailChapters[chapter.index] ?: 0) + 1
                contentLoadFinish(chapter, null, book)
            })
        }
    }

    fun loadContent() {
        loadContent(durChapterIndex)
    }

    fun loadContent(
        index: Int,
    ) {
        if (chapterChanged) {
            removeLoading(index)
        }
        if (addLoading(index)) {
            Coroutine.async {
                val book = book!!
                appDb.bookChapterDao.getChapter(book.bookUrl, index)?.let { chapter ->
                    BookHelp.getContent(book, chapter)?.let {
                        contentLoadFinish(chapter, it, book)
                    } ?: download(
                        downloadScope,
                        chapter,
                    )
                } ?: removeLoading(index)
            }.onError {
                removeLoading(index)
                AppLog.put("加载正文出错\n${it.localizedMessage}")
            }
        }
    }


    /**
     * 注册回调
     */
    fun register(cb: Callback) {
        mCallback = cb
    }

    /**
     * 取消注册回调
     */
    fun unregister() {
        inBookshelf = false
        tocChanged = false
        chapterChanged = false
        book = null
        durChapterIndex = 0
        chapterSize = 0
        durChapterPos = 0
        durChapterImageCount = 0
        bookSource = null
        chapterTitle = ""
        loadingChapters.clear()
        simulatedChapterSize = 0
        mCallback = null
        gameOver = false
        preDownloadTask?.cancel()
        preDownloadTask = null
        downloadedChapters.clear()
        downloadFailChapters.clear()
        downloadScope.coroutineContext.cancelChildren()
        coroutineContext.cancelChildren()
    }

    /**
     * 内容加载完成
     */
    suspend fun contentLoadFinish(
        chapter: BookChapter,
        content: String?,
        book: Book,
    ) {
        chapterTitle = chapter.title
        removeLoading(chapter.index)
        if (chapter.index !in durChapterIndex - 1..durChapterIndex + 1) {
            return
        }
        if (content == null) {
            mCallback?.loadFail("加载内容失败")
            return
        }
        if (content.isNotEmpty()) {
            val list = flow {
                val matcher = AppPattern.imgPattern.matcher(content)
                while (matcher.find()) {
                    val src = matcher.group(1) ?: continue
                    val mSrc = NetworkUtils.getAbsoluteURL(chapter.url, src)
                    emit(mSrc)
                }
            }.distinctUntilChanged().mapIndexed { index, src ->
                MangaContent(
                    chapterSize = chapterSize,
                    mChapterIndex = durChapterIndex,
                    nextChapterIndex = durChapterIndex.plus(1),
                    mImageUrl = src,
                    mDurChapterPos = index,
                    mChapterName = chapterTitle
                )
            }.toList().apply {
                this.forEach {
                    it.mDurChapterCount = this.size
                }
                durChapterImageCount = this.size
            }
            val contentList = mutableListOf<Any>()
            contentList.add(
                ReaderLoading(
                    durChapterIndex,
                    "阅读 ${chapter.title}",
                    mNextChapterIndex = durChapterIndex.plus(1)
                )
            )
            contentList.addAll(list)
            contentList.add(
                ReaderLoading(
                    durChapterIndex,
                    "已读完 ${chapter.title}",
                    mNextChapterIndex = durChapterIndex.plus(1)
                )
            )
            mCallback?.loadContentFinish(contentList)
        }
        mCallback?.loadComplete()
    }

    /**
     * 加载下一章
     */
    fun moveToNextChapter(index: Int) {

        if (loading(index)) {
            return
        }

        if (index > chapterSize - 1) {
            upToc(index)
            return
        }
        if (durChapterIndex < simulatedChapterSize - 1) {
            durChapterPos = 0
            durChapterIndex = index
            saveRead()
            loadContent(durChapterIndex)
            AppLog.putDebug("moveToNextChapter-curPageChanged()")
            curPageChanged()
        } else {
            AppLog.putDebug("跳转下一章失败,没有下一章")
        }
    }

    fun curPageChanged() {
        upReadTime()
        preDownload()
    }

    @Synchronized
    fun upToc(index: Int) {
        val bookSource = bookSource ?: return
        val book = book ?: return
        if (!book.canUpdate) return
        if (System.currentTimeMillis() - book.lastCheckTime < 600000) {
            mCallback?.noData()
            return
        }
        book.lastCheckTime = System.currentTimeMillis()
        WebBook.getChapterList(this, bookSource, book).onSuccess(IO) { cList ->
            if (book.bookUrl == ReadManga.book?.bookUrl
                && cList.size > chapterSize
            ) {
                appDb.bookChapterDao.delByBook(book.bookUrl)
                appDb.bookChapterDao.insert(*cList.toTypedArray())
                saveRead()
                durChapterPos = 0
                durChapterIndex = index
                chapterSize = cList.size
                simulatedChapterSize = book.simulatedTotalChapterNum()
                loadContent(durChapterIndex)
            } else {
                durChapterIndex = durChapterIndex.minus(1)
                saveRead()
                gameOver = true
                mCallback?.noData()
            }
        }.onError {
            mCallback?.noData()
        }
    }

    private fun downloadNetworkContent(
        bookSource: BookSource,
        scope: CoroutineScope,
        chapter: BookChapter,
        book: Book,
        success: suspend (String) -> Unit = {},
        error: suspend () -> Unit = {},
    ) {
        WebBook.getContent(
            scope,
            bookSource,
            book,
            chapter,
            start = CoroutineStart.LAZY,
            executeContext = IO,
            needSave = true
        ).onSuccess { content ->
            success.invoke(content)
        }.onError {
            error.invoke()
        }.start()
    }

    private fun preDownload() {
        if (book?.isLocal == true) return
        executor.execute {
            if (AppConfig.preDownloadNum < 2) {
                return@execute
            }
            preDownloadTask?.cancel()
            preDownloadTask = launch(IO) {
                //预下载
                launch {
                    val maxChapterIndex =
                        min(durChapterIndex + AppConfig.preDownloadNum, chapterSize)
                    for (i in durChapterIndex.plus(2)..maxChapterIndex) {
                        if (downloadedChapters.contains(i)) continue
                        if ((downloadFailChapters[i] ?: 0) >= 3) continue
                        downloadIndex(i)
                    }
                }
                launch {
                    val minChapterIndex = durChapterIndex - min(5, AppConfig.preDownloadNum)
                    for (i in durChapterIndex.minus(2) downTo minChapterIndex) {
                        if (downloadedChapters.contains(i)) continue
                        if ((downloadFailChapters[i] ?: 0) >= 3) continue
                        downloadIndex(i)
                    }
                }
            }
        }
    }

    private suspend fun downloadIndex(index: Int) {
        if (index < 0) return
        if (index > chapterSize - 1) {
            upToc()
            return
        }
        val book = book ?: return
        if (addLoading(index)) {
            try {
                appDb.bookChapterDao.getChapter(book.bookUrl, index)?.let { chapter ->
                    if (BookHelp.hasContent(book, chapter)) {
                        removeLoading(chapter.index)
                        downloadedChapters.add(chapter.index)
                    } else {
                        delay(1000)
                        download(downloadScope, chapter)
                    }
                } ?: removeLoading(index)
            } catch (_: Exception) {
                removeLoading(index)
            }
        }
    }

    @Synchronized
    fun upToc() {
        val bookSource = bookSource ?: return
        val book = book ?: return
        if (!book.canUpdate) return
        if (System.currentTimeMillis() - book.lastCheckTime < 600000) return
        book.lastCheckTime = System.currentTimeMillis()
        WebBook.getChapterList(this, bookSource, book).onSuccess(IO) { cList ->
            if (book.bookUrl == ReadManga.book?.bookUrl
                && cList.size > chapterSize
            ) {
                appDb.bookChapterDao.delByBook(book.bookUrl)
                appDb.bookChapterDao.insert(*cList.toTypedArray())
                saveRead()
                chapterSize = cList.size
                simulatedChapterSize = book.simulatedTotalChapterNum()
            }
        }
    }

    fun uploadProgress(successAction: (() -> Unit)? = null) {
        book?.let {
            launch(IO) {
                AppWebDav.uploadBookProgress(it)
                ensureActive()
                it.update()
                successAction?.invoke()
            }
        }
    }


    /**
     * 同步阅读进度
     * 如果当前进度快于服务器进度或者没有进度进行上传，如果慢与服务器进度则执行传入动作
     */
    fun syncProgress(
        newProgressAction: ((progress: BookProgress) -> Unit)? = null,
        uploadSuccessAction: (() -> Unit)? = null,
        syncSuccessAction: (() -> Unit)? = null,
    ) {
        if (!AppConfig.syncBookProgress) return
        book?.let {
            Coroutine.async {
                AppWebDav.getBookProgress(it)
            }.onError {
                AppLog.put("拉取阅读进度失败", it)
            }.onSuccess { progress ->
                if (progress == null || progress.durChapterIndex < it.durChapterIndex ||
                    (progress.durChapterIndex == it.durChapterIndex
                            && progress.durChapterPos < it.durChapterPos)
                ) {
                    // 服务器没有进度或者进度比服务器快，上传现有进度
                    Coroutine.async {
                        AppWebDav.uploadBookProgress(BookProgress(it), uploadSuccessAction)
                        it.update()
                    }
                } else if (progress.durChapterIndex > it.durChapterIndex ||
                    progress.durChapterPos > it.durChapterPos
                ) {
                    // 进度比服务器慢，执行传入动作
                    newProgressAction?.invoke(progress)
                } else {
                    syncSuccessAction?.invoke()
                }
            }
        }
    }

    fun setProgress(progress: BookProgress) {
        if (progress.durChapterIndex < chapterSize &&
            (durChapterIndex != progress.durChapterIndex
                    || durChapterPos != progress.durChapterPos)
        ) {
            chapterChanged = true
            if (progress.durChapterIndex == durChapterIndex) {
                durChapterPos = progress.durChapterPos
                mCallback?.adjustProgress()
            } else {
                durChapterIndex = progress.durChapterIndex
                durChapterPos = progress.durChapterPos
                loadContent()
            }
            saveRead()
        }
    }

    interface Callback {
        fun loadContentFinish(list: MutableList<Any>)
        fun loadComplete()
        fun loadFail(msg: String)
        fun noData()
        fun adjustProgress()
        fun sureNewProgress(progress: BookProgress)
    }
}