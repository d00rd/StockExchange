package org.example.exchange;

import java.math.BigDecimal;

public record Trade(long id, String symbol, String buyer, String seller, long buyOrderId, long sellOrderId,
                    int quantity, BigDecimal price, long timestamp) {
}
