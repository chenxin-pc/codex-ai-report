package com.example.aimilvusweb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.report-quality")
public class ReportQualityProperties {

    private final Chunk chunk = new Chunk();
    private final Retrieval retrieval = new Retrieval();

    public Chunk getChunk() {
        return chunk;
    }

    public Retrieval getRetrieval() {
        return retrieval;
    }

    public static class Chunk {
        private int childTargetTokens = 700;
        private int childMaxTokens = 1100;
        private int parentTargetTokens = 3000;
        private int parentMaxTokens = 5200;
        private int overlapTokens = 120;
        private int minSliceTokenCount = 30;

        public int getChildTargetTokens() {
            return childTargetTokens;
        }

        public void setChildTargetTokens(int childTargetTokens) {
            this.childTargetTokens = childTargetTokens;
        }

        public int getChildMaxTokens() {
            return childMaxTokens;
        }

        public void setChildMaxTokens(int childMaxTokens) {
            this.childMaxTokens = childMaxTokens;
        }

        public int getParentTargetTokens() {
            return parentTargetTokens;
        }

        public void setParentTargetTokens(int parentTargetTokens) {
            this.parentTargetTokens = parentTargetTokens;
        }

        public int getParentMaxTokens() {
            return parentMaxTokens;
        }

        public void setParentMaxTokens(int parentMaxTokens) {
            this.parentMaxTokens = parentMaxTokens;
        }

        public int getOverlapTokens() {
            return overlapTokens;
        }

        public void setOverlapTokens(int overlapTokens) {
            this.overlapTokens = overlapTokens;
        }

        public int getMinSliceTokenCount() {
            return minSliceTokenCount;
        }

        public void setMinSliceTokenCount(int minSliceTokenCount) {
            this.minSliceTokenCount = minSliceTokenCount;
        }
    }

    public static class Retrieval {
        private int initialTopK = 20;
        private int finalTopK = 5;
        private double minSimilarityScore = 0.0D;
        private boolean rerankEnabled = false;
        private int maxParentContextTokens = 4500;

        public int getInitialTopK() {
            return initialTopK;
        }

        public void setInitialTopK(int initialTopK) {
            this.initialTopK = initialTopK;
        }

        public int getFinalTopK() {
            return finalTopK;
        }

        public void setFinalTopK(int finalTopK) {
            this.finalTopK = finalTopK;
        }

        public double getMinSimilarityScore() {
            return minSimilarityScore;
        }

        public void setMinSimilarityScore(double minSimilarityScore) {
            this.minSimilarityScore = minSimilarityScore;
        }

        public boolean isRerankEnabled() {
            return rerankEnabled;
        }

        public void setRerankEnabled(boolean rerankEnabled) {
            this.rerankEnabled = rerankEnabled;
        }

        public int getMaxParentContextTokens() {
            return maxParentContextTokens;
        }

        public void setMaxParentContextTokens(int maxParentContextTokens) {
            this.maxParentContextTokens = maxParentContextTokens;
        }
    }
}
