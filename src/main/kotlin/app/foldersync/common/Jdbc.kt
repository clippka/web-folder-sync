package app.foldersync.common

import app.foldersync.common.args
import org.springframework.jdbc.core.simple.JdbcClient

/** Positional parameters that may be null (SQL NULL); `JdbcClient.args(vararg)` rejects nulls under strict nullability. */
fun JdbcClient.StatementSpec.args(vararg values: Any?): JdbcClient.StatementSpec = params(values.asList())
