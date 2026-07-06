package com.superchargedserver.ai.support;

/** Final validated output of the AI support pipeline. */
public record AIAnswer(String text, int confidence, boolean escalate, boolean fromCache) {
}