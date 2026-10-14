import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * End-to-end driver for Session 6A:
 *   1. index sample-repo/;
 *   2. for each question in {@link #QUESTIONS}, retrieve the top-k
 *      chunks and ask the LLM for an answer.
 *
 * Students add their own questions to {@link #QUESTIONS} (task 5 of
 * handson6.tex) and re-run with {@code make run}.
 *
 * The default build uses {@link StubEmbedder} + {@link StubLlmClient}
 * so the pipeline runs offline.  Once you have run
 * {@code make langchain4j} and copied {@code example/OllamaLlm.java}
 * into {@code src/}, swap the two construction lines below.
 */
public class Main {

    /**
     * Task (5): add nine more questions targeting the sample-repo/
     * library-management domain (books, members, loans, fines).
     */
    static final String[] QUESTIONS = {
        "Where is the fine for an overdue loan computed?",
        "What class holds the catalogue of books, and how is it indexed?",
        "How does the system decide a loan has been returned?",
        "What is the maximum fine the policy allows?",
        "Which methods write to the loan registry?",
        "How many books can a member have on loan at the same time?",
        "How long is the default loan term?",
        "How does the report service find overdue loans?",
        "When are two Book objects considered equal?",
        "How does searching for books by title work?",
        // Task (8): answer is NOT in the corpus -- the model must refuse.
        "Which Kafka topic does the library publish loan events to?",
    };

    private static final int TOP_K = 8;

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : "sample-repo");

        // -----------------------------------------------------------
        // 1. Pipeline construction.
        // -----------------------------------------------------------
        Embedder    emb = new MiniLmEmbedder();    // was: new StubEmbedder()
        LlmClient   llm = new OllamaLlm();         // was: new StubLlmClient()
        RepoIndexer idx = new RepoIndexer(emb);
        System.out.println("Embedder: " + emb.modelId() + "   LLM: " + llm
                + "   chunker: " + (System.getenv("CHUNKER") == null ? "method" : System.getenv("CHUNKER")));

        // -----------------------------------------------------------
        // 2. Index the corpus; report count + wall-clock time.
        // -----------------------------------------------------------
        Instant t0 = Instant.now();
        idx.indexDirectory(dir);
        Duration elapsed = Duration.between(t0, Instant.now());
        System.out.printf("Indexed %d chunk(s) from %s in %d ms%n%n",
                idx.size(), dir, elapsed.toMillis());

        // -----------------------------------------------------------
        // 3. Ask each question.  Print retriever hits + LLM answer.
        //    (retrieve() and answer() both re-rank; the redundancy is
        //    deliberate so the trace shows what the retriever picked.)
        // -----------------------------------------------------------
        RagAssistant ra = new RagAssistant(
                idx, emb, llm, Path.of("prompts/rag.txt"));
        for (String q : QUESTIONS) {
            System.out.println("=== Q: " + q);
            List<Chunk> hits = ra.retrieve(q, TOP_K);
            for (Chunk c : hits) {
                System.out.println("  hit: " + c.source()
                        + ":" + c.startLine() + "-" + c.endLine());
            }
            System.out.println("--- A: " + ra.answer(q, TOP_K));
            System.out.println();
        }
    }
}
