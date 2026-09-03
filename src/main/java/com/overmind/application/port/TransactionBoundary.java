package com.overmind.application.port;

import java.util.function.Supplier;

/** Runs application work atomically without making application code depend on Spring. */
public interface TransactionBoundary {

    <T> T inTransaction(Supplier<T> work);
}
