import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Walks a directory of .java files, splits each file into method-level
 * chunks, embeds each chunk, and stores the result in an in-memory index.
 *
 * Session 6A: complete {@link #chunkFile(Path)} and {@link #embed(String)}.
 *
 * Chunking strategy is chosen by the {@code CHUNKER} environment variable:
 * unset / {@code method} (default) splits at method boundaries;
 * {@code lines} splits every {@link #FIXED_WINDOW} lines (task 7's
 * deliberately bad chunker).
 */
public class RepoIndexer {

    /** Window size for the fixed-size "bad" chunker of task (7). */
    public static final int FIXED_WINDOW = 50;

    private static final Pattern TYPE_DECL =
            Pattern.compile("\\b(class|interface|enum|record)\\s+\\w+");

    private final Embedder embedder;
    private final String embedderId;
    private final boolean fixedWindow;
    private final List<Chunk> index = new ArrayList<>();

    public RepoIndexer(Embedder embedder) {
        this(embedder, "lines".equalsIgnoreCase(System.getenv("CHUNKER")));
    }

    public RepoIndexer(Embedder embedder, boolean fixedWindow) {
        this.embedder = embedder;
        this.embedderId = embedder.modelId();
        this.fixedWindow = fixedWindow;
    }

    /** Walks dir, indexing every *.java file below it. */
    public void indexDirectory(Path dir) throws IOException {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.filter(p -> p.toString().endsWith(".java"))
                 .sorted()
                 .forEach(this::indexFile);
        }
    }

    private void indexFile(Path file) {
        try {
            List<Chunk> chunks = fixedWindow ? chunkFixed(file, FIXED_WINDOW) : chunkFile(file);
            for (Chunk c : chunks) {
                float[] v = embed(c.content());
                index.add(c.withEmbedding(v));
            }
        } catch (IOException e) {
            throw new RuntimeException("failed to index " + file, e);
        }
    }

    /**
     * Splits the file into method-level chunks with a brace-balanced
     * scanner that skips string/char literals and comments.
     *
     * Every chunk is prefixed with the file path and the enclosing type
     * signature, and keeps the method's leading JavaDoc and annotations.
     * Each type additionally gets one "header" chunk holding its JavaDoc,
     * signature, fields and body-less (abstract/interface) methods, so
     * constants such as a fine cap are retrievable too.
     *
     * Falls back to a single whole-file chunk if nothing is detected.
     */
    protected List<Chunk> chunkFile(Path file) throws IOException {
        String src = Files.readString(file);
        String path = file.toString();
        List<Chunk> out = new ArrayList<>();

        // One frame per open '{'. Root (depth 0) is represented by a null type.
        Deque<Frame> stack = new ArrayDeque<>();
        int segStart = 0;   // start of the current member/statement at the innermost type level

        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                i = skipTo(src, i, "\n");
                continue;
            }
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                i = skipTo(src, i + 2, "*/");
                continue;
            }
            if (c == '"' && src.startsWith("\"\"\"", i)) {
                i = skipTo(src, i + 3, "\"\"\"");
                continue;
            }
            if (c == '"' || c == '\'') {
                i = skipLiteral(src, i, c);
                continue;
            }

            Frame top = stack.peek();
            boolean atTypeLevel = top == null || top.kind == Kind.TYPE;

            if (c == '{') {
                Frame f;
                if (atTypeLevel) {
                    int start = firstNonBlank(src, segStart, i);
                    String header = stripComments(src.substring(start, i));
                    if (TYPE_DECL.matcher(header).find()) {
                        f = new Frame(Kind.TYPE, start, collapse(header));
                        f.open = i;
                    } else if (top != null && isMethodHeader(header)) {
                        f = new Frame(Kind.METHOD, start, null);
                    } else {
                        f = new Frame(Kind.OTHER, start, null);
                    }
                } else {
                    f = new Frame(Kind.OTHER, i, null);
                }
                stack.push(f);
                segStart = i + 1;
            } else if (c == '}') {
                Frame f = stack.pop();
                Frame parent = stack.peek();
                if (f.kind == Kind.METHOD && parent != null) {
                    String body = src.substring(f.start, i + 1);
                    out.add(new Chunk(path, lineOf(src, f.start), lineOf(src, i),
                            prefix(path, stack) + body, null));
                } else if (f.kind == Kind.TYPE) {
                    emitTypeHeader(out, path, src, f, stack);
                }
                if (parent == null || parent.kind == Kind.TYPE) {
                    segStart = i + 1;
                }
            } else if (c == ';' && atTypeLevel) {
                // field, abstract method, package/import, enum constant list ...
                if (top != null) {
                    int start = firstNonBlank(src, segStart, i);
                    top.members.append("    ").append(src, start, i + 1).append('\n');
                    top.lastMemberEnd = i;
                }
                segStart = i + 1;
            }
            i++;
        }

        if (out.isEmpty()) {
            int lines = (int) src.lines().count();
            out.add(new Chunk(path, 1, lines, "// File: " + path + "\n" + src, null));
        }
        out.sort((a, b) -> Integer.compare(a.startLine(), b.startLine()));
        return out;
    }

    /** Task (7): the naive chunker -- a new chunk every {@code window} lines. */
    protected List<Chunk> chunkFixed(Path file, int window) throws IOException {
        List<String> lines = Files.readAllLines(file);
        List<Chunk> out = new ArrayList<>();
        for (int s = 0; s < lines.size(); s += window) {
            int e = Math.min(lines.size(), s + window);
            String body = String.join("\n", lines.subList(s, e));
            out.add(new Chunk(file.toString(), s + 1, e,
                    "// File: " + file + "\n" + body, null));
        }
        return out;
    }

    private void emitTypeHeader(List<Chunk> out, String path, String src, Frame type, Deque<Frame> outer) {
        int braceLine = lineOf(src, type.open);
        // type.start already points at the JavaDoc / annotations / modifiers.
        String decl = src.substring(type.start, type.open + 1);
        StringBuilder sb = new StringBuilder();
        sb.append("// File: ").append(path).append('\n');
        if (!outer.isEmpty()) {
            sb.append("// Enclosing: ").append(signatureChain(outer)).append('\n');
        }
        sb.append(decl).append('\n').append(type.members);
        if (type.members.length() > 0) sb.append("    ...\n");
        sb.append("}");
        int end = type.members.length() > 0 ? lineOf(src, type.lastMemberEnd) : braceLine;
        out.add(new Chunk(path, lineOf(src, type.start), Math.max(end, braceLine), sb.toString(), null));
    }

    private static String prefix(String path, Deque<Frame> stack) {
        return "// File: " + path + "\n// Class: " + signatureChain(stack) + "\n";
    }

    /** Outermost-first chain of enclosing type signatures, e.g. "public class A > static class B". */
    private static String signatureChain(Deque<Frame> stack) {
        List<String> sigs = new ArrayList<>();
        stack.descendingIterator().forEachRemaining(f -> {
            if (f.kind == Kind.TYPE) sigs.add(f.signature);
        });
        return String.join(" > ", sigs);
    }

    /** A '{' at type level opens a method/constructor iff its header has a parameter list and is not an initialiser. */
    private static boolean isMethodHeader(String header) {
        int paren = header.indexOf('(');
        if (paren < 0) return false;                      // static/instance initialiser block
        int eq = header.indexOf('=');
        return eq < 0 || eq > paren;                      // "x = new Foo() {" is a field
    }

    private static String stripComments(String s) {
        return s.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\n]*", " ");
    }

    /** Removes annotations, collapses whitespace: "public class Loan". */
    private static String collapse(String header) {
        return header.replaceAll("@\\w+(\\([^)]*\\))?", " ").replaceAll("\\s+", " ").trim();
    }

    private static int skipTo(String s, int from, String end) {
        int j = s.indexOf(end, from);
        return j < 0 ? s.length() : j + end.length();
    }

    private static int skipLiteral(String s, int from, char quote) {
        int j = from + 1;
        while (j < s.length()) {
            char c = s.charAt(j);
            if (c == '\\') { j += 2; continue; }
            if (c == quote || c == '\n') return j + 1;
            j++;
        }
        return j;
    }

    private static int firstNonBlank(String s, int from, int to) {
        while (from < to && Character.isWhitespace(s.charAt(from))) from++;
        return from;
    }

    /** 1-indexed line number of offset pos. */
    private static int lineOf(String s, int pos) {
        int line = 1;
        for (int j = 0; j < pos && j < s.length(); j++) {
            if (s.charAt(j) == '\n') line++;
        }
        return line;
    }

    private enum Kind { TYPE, METHOD, OTHER }

    private static final class Frame {
        final Kind kind;
        final int start;
        final String signature;
        final StringBuilder members = new StringBuilder();
        int open;            // offset of the type's opening '{'
        int lastMemberEnd;   // offset of the last field / abstract member's ';'

        Frame(Kind kind, int start, String signature) {
            this.kind = kind;
            this.start = start;
            this.signature = signature;
        }
    }

    /**
     * Delegates to the configured {@link Embedder}; swapping the stub for
     * a real model happens at construction time (see Main), not here.
     */
    protected float[] embed(String text) {
        return embedder.embed(text);
    }

    /** Identifier of the embedding model the index was built with. */
    public String getEmbedderId() {
        return embedderId;
    }

    public List<Chunk> getIndex() {
        return index;
    }

    public int size() {
        return index.size();
    }
}
