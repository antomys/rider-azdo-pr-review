package io.github.antomys.azdoprreview.toolwindow

import java.util.concurrent.ConcurrentHashMap

/** The file versions a user marked as viewed, per review. A mark only counts while the file's version is unchanged. */
interface ViewedFiles {
    /** Path to the version that was viewed. */
    fun viewed(review: String): Map<String, String>

    /** Marks [path] at [version] as viewed, or clears the mark when [version] is null. */
    fun setViewed(review: String, path: String, version: String?)

    class InMemory : ViewedFiles {
        private val marks = ConcurrentHashMap<String, MutableMap<String, String>>()

        override fun viewed(review: String): Map<String, String> = marks[review].orEmpty().toMap()

        override fun setViewed(review: String, path: String, version: String?) {
            val files = marks.getOrPut(review) { ConcurrentHashMap() }
            if (version == null) files.remove(path) else files[path] = version
        }
    }
}
