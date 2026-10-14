import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Query-time RAG assistant. Takes a natural-language query, embeds it,
 * pulls the top-k nearest chunks from the {@link RepoIndexer}'s index,
 * formats them into the RAG prompt template, and calls the LLM.
 *
 * Session 6A: complete {@link #retrieve(String, int)} and
 * {@link #buildPrompt(String, List)}.  Do NOT edit {@link #answer}.
 */
public class RagAssistant {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(query|context)\\}");

    private final RepoIndexer indexer;
    private final Embedder embedder;
    private final LlmClient llm;
    private final Path promptTemplate;

    public RagAssistant(RepoIndexer indexer, Embedder embedder, LlmClient llm, Path promptTemplate) {
        this.indexer = indexer;
        this.embedder = embedder;
        this.llm = llm;
        this.promptTemplate = promptTemplate;
    }

    public String answer(String query, int k) throws IOException {
        List<Chunk> hits = retrieve(query, k);
        String prompt = buildPrompt(query, hits);
        return llm.complete(prompt);
    }

    /**
     * Embeds the query, ranks every indexed chunk by cosine similarity,
     * and returns the top k (best first).
     *
     * @throws IllegalStateException if the index was built with a different
     *         embedding model than the one used for the query.
     */
    protected List<Chunk> retrieve(String query, int k) {
        if (!indexer.getEmbedderId().equals(embedder.modelId())) {
            throw new IllegalStateException("embedding model mismatch: index built with "
                    + indexer.getEmbedderId() + ", query embedded with " + embedder.modelId());
        }
        float[] q = embedder.embed(query);
        record Scored(Chunk chunk, double score) {}
        List<Scored> scored = new ArrayList<>();
        for (Chunk c : indexer.getIndex()) {
            scored.add(new Scored(c, cosine(q, c.embedding())));
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        List<Chunk> out = new ArrayList<>();
        for (int i = 0; i < Math.min(k, scored.size()); i++) {
            out.add(scored.get(i).chunk());
        }
        return out;
    }

    /**
     * Loads the template from {@link #promptTemplate} and substitutes
     * {@code {query}} and {@code {context}}; the context is one
     * "source:start-end" header plus content block per hit, in rank order.
     * Substitution is single-pass so placeholder-looking text inside a
     * chunk or the query is never expanded.
     */
    protected String buildPrompt(String query, List<Chunk> hits) throws IOException {
        StringBuilder ctx = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            Chunk c = hits.get(i);
            ctx.append("[").append(i + 1).append("] ")
               .append(c.source()).append(':').append(c.startLine()).append('-').append(c.endLine())
               .append('\n').append(c.content().strip()).append("\n\n");
        }
        String context = ctx.toString().strip();

        String template = Files.readString(promptTemplate);
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = m.group(1).equals("query") ? query : context;
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Full cosine (does not assume unit-length vectors). */
    static double cosine(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("dim mismatch: " + a.length + " vs " + b.length);
        }
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return (na == 0 || nb == 0) ? 0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
