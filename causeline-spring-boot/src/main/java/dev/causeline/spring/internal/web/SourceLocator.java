// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Finds the source file of an application class, so the UI can open it in the developer's editor.
 * The application runs from its project in development, so sources are under the working
 * directory: {@code src/main/java} (or {@code kotlin}, or {@code test}) of the project or one of its
 * modules. Only paths are ever returned, never file contents.
 */
public final class SourceLocator {

    /** Where a class's source is, and the line to open. */
    public record Location(String path, int line) {
    }

    private static final Set<String> SKIPPED = Set.of("node_modules", "target", "build", "out", "dist", ".git",
            ".gradle", ".idea", ".next", ".mvn", "bin");
    private static final Pattern CLASS_NAME = Pattern.compile("[\\w$]+(\\.[\\w$]+)*");
    private static final int MAX_DEPTH = 6;

    private final Path project;
    private volatile List<Path> roots;
    private final Map<String, Optional<Path>> files = new ConcurrentHashMap<>();

    public SourceLocator(Path project) {
        this.project = project.toAbsolutePath().normalize();
    }

    /**
     * @param className a top-level class name, e.g. {@code com.shop.OrderService}
     * @param method    the method to point at when no line is known; may be null
     * @param line      the line to open, or 0 to look for the method's declaration
     */
    public Optional<Location> locate(String className, String method, int line) {
        if (className == null || !CLASS_NAME.matcher(className).matches()) {
            return Optional.empty();
        }
        String outer = className.contains("$") ? className.substring(0, className.indexOf('$')) : className;
        return files.computeIfAbsent(outer, this::find)
                .map(file -> new Location(file.toString(), line > 0 ? line : declarationLine(file, method)));
    }

    private Optional<Path> find(String className) {
        String relative = className.replace('.', '/');
        for (Path root : roots()) {
            for (String extension : List.of(".java", ".kt")) {
                Path file = root.resolve(relative + extension);
                if (Files.isRegularFile(file)) {
                    return Optional.of(file);
                }
            }
        }
        return Optional.empty();
    }

    /** Source roots of the project and its modules, found once. */
    private List<Path> roots() {
        List<Path> found = roots;
        if (found == null) {
            found = new ArrayList<>();
            List<Path> result = found;
            try {
                Files.walkFileTree(project, Set.of(), MAX_DEPTH, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
                        String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                        if (!dir.equals(project) && SKIPPED.contains(name)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        Path parent = dir.getParent();
                        if ((name.equals("java") || name.equals("kotlin")) && parent != null
                                && parent.getParent() != null && parent.getParent().getFileName() != null
                                && parent.getParent().getFileName().toString().equals("src")) {
                            result.add(dir);
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException e) {
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                // No sources to offer; the UI shows the location as text.
            }
            // Main sources before tests, so a class name finds the production file first.
            found.sort((a, b) -> Boolean.compare(a.toString().contains("test"), b.toString().contains("test")));
            roots = found;
        }
        return found;
    }

    /** The first line that declares the method, or 1 when it isn't found. */
    static int declarationLine(Path file, String method) {
        if (method == null || method.isBlank()) {
            return 1;
        }
        Pattern declaration = Pattern.compile("^\\s*(?!return\\b)[\\w<>\\[\\],.?@ ]*\\b" + Pattern.quote(method) + "\\s*\\(");
        Pattern kotlin = Pattern.compile("\\bfun\\s+" + Pattern.quote(method) + "\\s*[(<]");
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String text = lines.get(i);
                if (kotlin.matcher(text).find() || (declaration.matcher(text).find() && !text.trim().endsWith(";"))) {
                    return i + 1;
                }
            }
        } catch (IOException | RuntimeException e) {
            // Fall back to the top of the file.
        }
        return 1;
    }
}
