package de.julielab.jcore.reader.cord19;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

public class Cord19FileVisitor extends SimpleFileVisitor<Path> {
    public static final Path END = Path.of("NO_MORE_FILES_JCORE_CORD19_READER");
    private final static Logger log = LoggerFactory.getLogger(Cord19FileVisitor.class);
    private BlockingQueue<Path> fileQueue = new ArrayBlockingQueue<>(50);
    private boolean walkFinished = false;

    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        try {
            if (!attrs.isDirectory() && (file.toString().endsWith(".json") || file.toString().endsWith(".json.gz")))
                fileQueue.put(file);
        } catch (InterruptedException e) {
            log.error("could not add file {} to the queue", file, e);
            throw new RuntimeException(e);
        }
        return FileVisitResult.CONTINUE;
    }

    /**
     * <p>Returns a number of files restricted by <tt>maxFiles</tt>.</p>
     * <p>Will return a list with the requested files or {@link #END}if the file walk has ended and there are
     * no files left.</p>
     *
     * @param maxFiles
     * @return
     */
    public List<Path> getFiles(int maxFiles) {
        List<Path> files = new ArrayList<>(maxFiles);
        try {
            // Ensure that we do not leave without a document as long as the walk has not yet
            // finished.
            while (files.isEmpty() && !walkFinished) {
                files.add(fileQueue.poll(1, TimeUnit.SECONDS));
            }
            fileQueue.drainTo(files, maxFiles-1);
            if (walkFinished)
                files.add(END);
        } catch (InterruptedException e) {
            log.error("Error while waiting for new documents.", e);
            throw new RuntimeException(e);
        }
        return files;
    }

    /**
     * Indicate the visitor that the walk has finished. When doing this, the remaining files in the internal queue
     * will be returned by {@link #getFiles(int)}. Then, {@link #getFiles(int)} will only return {@link #END}.
     */
    public void walkFinished() {
        walkFinished = true;
    }
}
