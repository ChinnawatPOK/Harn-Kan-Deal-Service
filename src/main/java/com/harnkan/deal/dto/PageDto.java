package com.harnkan.deal.dto;

import java.util.List;

/** {items, page, size, total} used by DR3 and DR5. */
public record PageDto<T>(List<T> items, int page, int size, long total) {
}
