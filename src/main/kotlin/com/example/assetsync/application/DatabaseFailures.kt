package com.example.assetsync.application

import java.sql.SQLException
import org.springframework.dao.DataAccessException
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.TransactionSystemException

/**
 * Whether this failure means the database could not serve the operation. Spring translates most
 * SQL errors into a [DataAccessException], but a transaction that cannot get a connection, or whose
 * rollback fails on a connection an outage broke, throws a transaction exception instead, and an
 * SQL error Spring cannot classify, such as a write to a read-only database, stays a jOOQ exception
 * around the [SQLException]. Other transaction exceptions, such as an unexpected rollback, are
 * programming errors. Only this exception and its direct cause count, so a service exception that
 * wraps a database error keeps its own meaning.
 */
fun Throwable.isDatabaseFailure(): Boolean =
    this is DataAccessException ||
        this is CannotCreateTransactionException ||
        this is TransactionSystemException ||
        cause is SQLException
