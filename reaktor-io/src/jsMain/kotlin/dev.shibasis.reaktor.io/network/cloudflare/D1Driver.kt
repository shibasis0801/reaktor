package dev.shibasis.reaktor.io.network.cloudflare
//
//import app.cash.sqldelight.Query
//import app.cash.sqldelight.Transacter
//import app.cash.sqldelight.db.QueryResult
//import app.cash.sqldelight.db.SqlCursor
//import app.cash.sqldelight.db.SqlDriver
//import app.cash.sqldelight.db.SqlPreparedStatement
//
//class D1Driver(): SqlDriver {
//    override fun <R> executeQuery(
//        identifier: Int?,
//        sql: String,
//        mapper: (SqlCursor) -> QueryResult<R>,
//        parameters: Int,
//        binders: (SqlPreparedStatement.() -> Unit)?
//    ): QueryResult<R> {
//        TODO("Not yet implemented")
//    }
//
//    override fun execute(
//        identifier: Int?,
//        sql: String,
//        parameters: Int,
//        binders: (SqlPreparedStatement.() -> Unit)?
//    ): QueryResult<Long> {
//        TODO("Not yet implemented")
//    }
//
//    override fun newTransaction(): QueryResult<Transacter.Transaction> {
//        TODO("Not yet implemented")
//    }
//
//    override fun currentTransaction(): Transacter.Transaction? {
//        TODO("Not yet implemented")
//    }
//
//    // D1 does not support listeners as of july 2024.
//    override fun addListener(vararg queryKeys: String, listener: Query.Listener) {}
//    override fun removeListener(vararg queryKeys: String, listener: Query.Listener) {}
//    override fun notifyListeners(vararg queryKeys: String) {}
//    override fun close() {}
//}