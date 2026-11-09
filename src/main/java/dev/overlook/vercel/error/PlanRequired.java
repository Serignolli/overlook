package dev.overlook.vercel.error;

public final class PlanRequired extends VercelException {

    public PlanRequired(String message) {
        super("PLAN_REQUIRED",
                message == null || message.isBlank() ? "Esta métrica requer Observability Plus." : message,
                "Disponível em planos Pro/Enterprise com Observability Plus.");
    }
}
