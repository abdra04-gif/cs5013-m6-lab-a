import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class RagPipelineTest {

    private static final Path FINE = Path.of("sample-repo/com/example/util/FinePolicy.java");
    private static final Path BOOK = Path.of("sample-repo/com/example/core/Book.java");

    private static RepoIndexer methodIndexer() {
        return new RepoIndexer(new StubEmbedder(), false);
    }

    @Test
    public void chunker_splitsAtMethodBoundaries() throws IOException {
        List<Chunk> chunks = methodIndexer().chunkFile(BOOK);
        // 1 type header + ctor + 5 getters + equals/hashCode/toString
        assertEquals(10, chunks.size());
        Chunk equals = chunks.stream().filter(c -> c.content().contains("boolean equals")).findFirst().orElseThrow();
        assertEquals(47, equals.startLine(), "chunk should start at the JavaDoc");
        assertEquals(55, equals.endLine());
    }

    @Test
    public void chunker_prefixesPathAndClassSignatureAndKeepsJavadoc() throws IOException {
        Chunk computeFine = methodIndexer().chunkFile(FINE).stream()
                .filter(c -> c.content().contains("public BigDecimal computeFine")).findFirst().orElseThrow();
        String text = computeFine.content();
        assertTrue(text.startsWith("// File: " + FINE), text);
        assertTrue(text.contains("// Class: class StandardFinePolicy implements FinePolicy"), text);
        assertTrue(text.contains("@Override"), text);

        Chunk equals = methodIndexer().chunkFile(BOOK).stream()
                .filter(c -> c.content().contains("boolean equals")).findFirst().orElseThrow();
        assertTrue(equals.content().contains("Two books are equal iff they share an ISBN."));
    }

    @Test
    public void chunker_emitsTypeHeaderWithFields() throws IOException {
        List<Chunk> chunks = methodIndexer().chunkFile(FINE);
        assertTrue(chunks.stream().anyMatch(c -> c.content().contains("CAP = new BigDecimal(\"500.00\")")
                && !c.content().contains("computeFine(Loan loan, LocalDate today) {")));
    }

    @Test
    public void chunker_ignoresBracesInStringsAndComments(@org.junit.jupiter.api.io.TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("Tricky.java");
        Files.writeString(f, """
                /** Uses {@link Object} in docs. */
                public class Tricky {
                    // a stray } in a comment
                    String s = "}{";
                    char c = '{';
                    void a() { if (true) { s = "}"; } }
                    /* } */ void b() {}
                }
                """);
        List<Chunk> chunks = methodIndexer().chunkFile(f);
        assertEquals(3, chunks.size(), chunks.toString());
        assertTrue(chunks.get(0).content().contains("public class Tricky"));
    }

    @Test
    public void fixedWindowChunker_splitsEvery50Lines() throws IOException {
        RepoIndexer idx = new RepoIndexer(new StubEmbedder(), true);
        idx.indexDirectory(Path.of("sample-repo"));
        assertEquals(12, idx.size());
        assertTrue(idx.getIndex().stream().allMatch(c -> c.endLine() - c.startLine() < RepoIndexer.FIXED_WINDOW));
    }

    @Test
    public void retrieve_ranksByCosineNotIndexOrder() throws IOException {
        RepoIndexer idx = methodIndexer();
        idx.indexDirectory(Path.of("sample-repo"));
        Chunk last = idx.getIndex().get(idx.size() - 1);
        RagAssistant ra = new RagAssistant(idx, new StubEmbedder(), null, Path.of("prompts/rag.txt"));
        // StubEmbedder is a hash: the exact text of a chunk is its own nearest neighbour.
        List<Chunk> top = ra.retrieve(last.content(), 3);
        assertEquals(3, top.size());
        assertSame(last, top.get(0));
    }

    @Test
    public void retrieve_failsLoudlyOnEmbeddingModelMismatch() throws IOException {
        RepoIndexer idx = methodIndexer();
        idx.indexDirectory(Path.of("sample-repo"));
        Embedder other = new Embedder() {
            public float[] embed(String t) { return new float[StubEmbedder.DIM]; }
            public String modelId() { return "some-other-model/v2"; }
        };
        RagAssistant ra = new RagAssistant(idx, other, null, Path.of("prompts/rag.txt"));
        assertThrows(IllegalStateException.class, () -> ra.retrieve("fine", 4));
    }

    @Test
    public void buildPrompt_substitutesQueryAndContext() throws IOException {
        RagAssistant ra = new RagAssistant(methodIndexer(), new StubEmbedder(), null, Path.of("prompts/rag.txt"));
        Chunk c = new Chunk("A.java", 3, 5, "int x = 1; // {query} $1", null);
        String p = ra.buildPrompt("What is x?", List.of(c));
        assertFalse(p.contains("{context}"));
        assertTrue(p.contains("What is x?"));
        assertTrue(p.contains("[1] A.java:3-5\nint x = 1; // {query} $1"), p);
        assertTrue(p.contains("I do not find that in the passages."));
    }
}
