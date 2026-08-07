package com.example.service;

import com.example.entity.OrderRequest;

/** Business restrictions that must be enforced before an order is processed. */
public final class OrderRequestPolicy {
    private static final Integer IBKR_ALLOWED_USER_ID = 148;

    private OrderRequestPolicy() {
    }

    public static void validateIbkrUser(OrderRequest request) {
        if (request == null || (!isIbkr(request.getLongApi()) && !isIbkr(request.getShortApi()))) {
            return;
        }

        Integer userId = request.getTemplate() == null ? null : request.getTemplate().getUr();
        if (!IBKR_ALLOWED_USER_ID.equals(userId)) {
            throw new IllegalArgumentException(
                    "IBKR orders are only allowed for template.ur=148; actual ur=" + userId);
        }
    }

    private static boolean isIbkr(OrderRequest.ExchangeApi api) {
        return api != null && api.getEe() != null && "ibkr".equalsIgnoreCase(api.getEe().trim());
    }
}
