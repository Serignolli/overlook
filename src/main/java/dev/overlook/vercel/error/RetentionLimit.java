package dev.overlook.vercel.error;

/**
 * O período pedido começa antes do que o plano permite consultar (ex.: Hobby só libera os
 * últimos 31 dias de Web Analytics).
 */
public final class RetentionLimit extends VercelException {

    private final Integer days;

    public RetentionLimit(Integer days) {
        this(days, false);
    }

    /**
     * @param historyEnabled se o histórico local está ligado (e mesmo assim não cobre o período)
     */
    public RetentionLimit(Integer days, boolean historyEnabled) {
        super("RETENTION_LIMIT",
                (days == null ? "O período pedido é mais antigo do que o plano permite consultar."
                        : "Seu plano dá acesso só aos últimos " + days + " dias de Web Analytics.")
                        + (historyEnabled ? " O histórico local ainda não tem dados desse período." : ""),
                historyEnabled ? "Escolha um período menor."
                        : "Escolha um período menor, ou ative o histórico local nas configurações para guardar os "
                                + "dados daqui para a frente.");
        this.days = days;
    }

    /** Dias que o plano libera, quando a Vercel informou; senão {@code null}. */
    public Integer days() {
        return days;
    }
}
