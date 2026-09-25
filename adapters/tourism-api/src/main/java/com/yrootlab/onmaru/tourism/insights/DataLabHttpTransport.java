package com.yrootlab.onmaru.tourism.insights;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;

/** Test seam around the JDK HTTP client. */
@FunctionalInterface
public interface DataLabHttpTransport {

    DataLabHttpResponse get(URI uri, Duration timeout) throws IOException, InterruptedException;
}
