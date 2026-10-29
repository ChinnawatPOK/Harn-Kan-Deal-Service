package com.harnkan.deal.web;

/** The caller, as told by the gateway (X-User-Id / X-User-Name). */
public record CurrentUser(String id, String name) {
}
