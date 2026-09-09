package com.review.worker.llama;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Wraps an {@link InputStream} and throws as soon as more than {@code maxBytes} have been read, instead
 * of buffering the whole body first and checking its length afterward.
 *
 * <p>This is the actual mechanism behind WSR-04/WSR-05 ("the Worker must not buffer an unbounded llama
 * response into memory"): a check performed only after a full read (e.g. on {@code String.length()})
 * would already have allocated the oversized buffer by the time it fires, defeating the point of a
 * memory bound. By failing mid-stream, at most {@code maxBytes + 1} bytes are ever held.
 */
final class BoundedInputStream extends FilterInputStream {

    private final long maxBytes;
    private long bytesRead;

    BoundedInputStream(InputStream delegate, long maxBytes) {
        super(delegate);
        this.maxBytes = maxBytes;
    }

    @Override
    public int read() throws IOException {
        int b = super.read();
        if (b != -1) {
            bytesRead++;
            checkLimit();
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int n = super.read(b, off, len);
        if (n > 0) {
            bytesRead += n;
            checkLimit();
        }
        return n;
    }

    private void checkLimit() throws IOException {
        if (bytesRead > maxBytes) {
            throw new ResponseTooLargeException("llama-server response exceeded " + maxBytes + " bytes");
        }
    }

    /** Internal marker exception; always translated to {@link com.review.worker.error.LlamaException} by the caller. */
    static final class ResponseTooLargeException extends IOException {
        ResponseTooLargeException(String message) {
            super(message);
        }
    }
}
