package io.github.stream29.codex.lite.utils.searchindex

import org.gnit.lucenekmp.analysis.standard.StandardAnalyzer
import org.gnit.lucenekmp.document.Document
import org.gnit.lucenekmp.document.Field
import org.gnit.lucenekmp.document.StringField
import org.gnit.lucenekmp.document.TextField
import org.gnit.lucenekmp.index.DirectoryReader
import org.gnit.lucenekmp.index.IndexWriter
import org.gnit.lucenekmp.index.IndexWriterConfig
import org.gnit.lucenekmp.search.IndexSearcher
import org.gnit.lucenekmp.search.similarities.BM25Similarity
import org.gnit.lucenekmp.store.ByteBuffersDirectory
import org.gnit.lucenekmp.util.QueryBuilder

internal actual fun <T> createPlatformSearchIndex(documents: List<SearchDocument<T>>): SearchIndex<T> =
    LuceneSearchIndex(documents)

private class LuceneSearchIndex<T>(
    private val documents: List<SearchDocument<T>>,
) : SearchIndex<T> {
    private val analyzer = StandardAnalyzer()
    private val directory = ByteBuffersDirectory()
    private val reader: DirectoryReader
    private val searcher: IndexSearcher

    init {
        IndexWriter(directory, IndexWriterConfig(analyzer)).use { writer ->
            documents.forEachIndexed { index, document ->
                writer.addDocument(
                    Document().apply {
                        add(StringField(IdField, index.toString(), Field.Store.YES))
                        add(TextField(SearchTextField, document.text, Field.Store.NO))
                    },
                )
            }
            writer.commit()
        }

        reader = DirectoryReader.open(directory)
        searcher = IndexSearcher(reader).apply {
            similarity = BM25Similarity()
        }
    }

    override fun search(query: String, limit: Int): List<T> {
        val luceneQuery = QueryBuilder(analyzer).createBooleanQuery(SearchTextField, query) ?: return emptyList()
        val storedFields = searcher.storedFields()
        return searcher.search(luceneQuery, limit)
            .scoreDocs
            .mapNotNull { scoreDoc ->
                storedFields.document(scoreDoc.doc)
                    .get(IdField)
                    ?.toIntOrNull()
                    ?.let(documents::getOrNull)
                    ?.value
            }
    }
}

private const val IdField = "id"
private const val SearchTextField = "search_text"
