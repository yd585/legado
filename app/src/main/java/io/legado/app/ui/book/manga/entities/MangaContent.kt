package io.legado.app.ui.book.manga.entities

data class MangaContent(
    var mChapterIndex: Int = 0,//总章节位置
    var chapterSize: Int,//总章节数量
    var nextChapterIndex: Int = 0,//下一章
    val mImageUrl: String = "",//当前URL
    var mDurChapterPos: Int = 0,//当前章节位置
    var mDurChapterCount: Int = 0,//当前章节内容总数
    var mChapterName: String = "",//章节名称
)