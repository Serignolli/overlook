package dev.overlook.web.dto;

import dev.overlook.config.AppConfig;

/**
 * @param config          o que está em {@code config.json}
 * @param runningPort     porta em uso agora (flags podem sobrescrever o arquivo)
 * @param requiresRestart verdadeiro se a mudança só vale depois de reiniciar
 */
public record SettingsView(AppConfig config, int runningPort, boolean demo, String configPath, boolean requiresRestart) {
}
