import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;

/**
 * Real semantic embedder: all-MiniLM-L6-v2 (384-dim), bundled in the
 * langchain4j jar and run in-process via ONNX -- no network, no key.
 * Requires {@code make langchain4j}.
 */
public class MiniLmEmbedder implements Embedder {

    public static final String MODEL_ID = "all-MiniLM-L6-v2/langchain4j-0.36.0";

    private final EmbeddingModel model = new AllMiniLmL6V2EmbeddingModel();

    @Override
    public float[] embed(String text) {
        return model.embed(text).content().vector();
    }

    @Override
    public String modelId() {
        return MODEL_ID;
    }

    @Override
    public String toString() {
        return "MiniLmEmbedder[" + MODEL_ID + "]";
    }
}
