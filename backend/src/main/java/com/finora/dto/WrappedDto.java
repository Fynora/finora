package com.finora.dto;

import java.util.List;

public record WrappedDto(int year, int landmarksReached, int goalContributions, List<String> landmarkTitles) {}
