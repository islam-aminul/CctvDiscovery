package com.cctv.discovery.util;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The deadline on FFmpeg work has to hold even when the work ignores being
 * interrupted, because that is what a thread inside native FFmpeg does.
 *
 * <p>On a live Hikvision recorder, {@code avformat_find_stream_info} did not
 * return for an hour and the whole scan waited with it. The blocking work here
 * stands in for that native call: it carries on through interrupts until it is
 * released, exactly as FFmpeg did.
 */
class FFmpegDeadlineTest {

    private static final String FIXTURE = "/media/sample-320x240-10fps.mp4";

    @TempDir
    static Path tempDir;

    private static Path fixture;

    @BeforeAll
    static void unpackFixture() throws Exception {
        fixture = tempDir.resolve("sample.mp4");
        try (InputStream in = FFmpegDeadlineTest.class.getResourceAsStream(FIXTURE)) {
            assertNotNull(in, "test fixture " + FIXTURE + " is missing");
            Files.copy(in, fixture, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Test
    @DisplayName("Work that ignores interruption still cannot hold the caller past the limit")
    void deadlineHoldsAgainstUninterruptibleWork() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean finishedLate = new AtomicBoolean();

        long started = System.nanoTime();
        assertThrows(TimeoutException.class, () -> FFmpegSupport.withDeadline(
                new FFmpegFrameGrabber(fixture.toFile()), 500, g -> {
                    // Like a thread inside native code: interrupts change nothing.
                    while (release.getCount() > 0) {
                        try {
                            release.await();
                        } catch (InterruptedException ignored) {
                            // carry on, as FFmpeg would
                        }
                    }
                    finishedLate.set(true);
                    return "too late";
                }));
        long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertTrue(waitedMs < 3_000, "the caller waited " + waitedMs + " ms for a 500 ms limit");

        // The abandoned work is still running; letting it go must not break anything.
        release.countDown();
        for (int i = 0; i < 50 && !finishedLate.get(); i++) {
            Thread.sleep(20);
        }
        assertTrue(finishedLate.get(), "the abandoned work finishes on its own thread");
    }

    @Test
    @DisplayName("Work that finishes in time returns its result")
    void returnsResultInTime() throws Exception {
        int width = FFmpegSupport.withDeadline(new FFmpegFrameGrabber(fixture.toFile()), 10_000,
                FFmpegFrameGrabber::getImageWidth);
        assertEquals(320, width);
    }

    @Test
    @DisplayName("A failure inside the work comes back as itself, not wrapped")
    void propagatesFailures() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> FFmpegSupport.withDeadline(new FFmpegFrameGrabber(fixture.toFile()), 10_000, g -> {
                    throw new IllegalStateException("decoder said no");
                }));
        assertEquals("decoder said no", thrown.getMessage());
    }

    @Test
    @DisplayName("Closing a grabber later returns at once, whatever the grabber is doing")
    void closeLaterDoesNotBlock() throws Exception {
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(fixture.toFile());
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // Hold the grabber's own lock the way a stuck open does.
        Thread holder = Thread.ofPlatform().start(() -> {
            synchronized (grabber) {
                holding.countDown();
                try {
                    release.await();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        holding.await();

        long started = System.nanoTime();
        FFmpegSupport.closeLater(grabber);
        long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        release.countDown();
        holder.join();
        assertTrue(tookMs < 500, "closeLater blocked for " + tookMs + " ms; the Stop button calls it");
    }
}
