import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.chat.ChatLanguageModel;

import java.time.Duration;

/**
 * LlmClient backed by a local Ollama daemon (see example/OllamaLlm.java).
 * Model defaults to llama3.2:3b (fits an 8 GB machine); override with
 * the OLLAMA_MODEL environment variable, e.g. OLLAMA_MODEL=llama3.1:8b.
 */
public class OllamaLlm implements LlmClient {
	private static final String MODEL =
			System.getenv().getOrDefault("OLLAMA_MODEL", "llama3.2:3b");

	private final ChatLanguageModel model = OllamaChatModel.builder()
						.baseUrl("http://localhost:11434")
						.modelName(MODEL)
						.temperature(0.0)
						.timeout(Duration.ofMinutes(3))
						.build();
	@Override
	public String complete(String prompt) {
		return model.generate(prompt);
	}

	@Override
	public String toString() {
		return "OllamaLlm[" + MODEL + "]";
	}
}
