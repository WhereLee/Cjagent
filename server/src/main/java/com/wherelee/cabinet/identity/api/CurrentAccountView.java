package com.wherelee.cabinet.identity.api;

import java.util.List;

public record CurrentAccountView(Long userId,
                                 String username,
                                 Long tenantId,
                                 String end,
                                 List<String> authorities) {
}
