package me.xraid.xraytracker;

import me.xraid.xraytracker.models.MiningRecord;
import java.util.List;

public class XrayAnalytics {

    public record AnalyticsResult(
        double xrayProbability, 
        double oreRatio, 
        double pathComplexity, 
        int sharpTurns,
        String suspicionLevel
    ) {}

    public static AnalyticsResult analyze(List<MiningRecord> records) {
        if (records == null || records.isEmpty() || records.size() < 10) {
            return new AnalyticsResult(0.0, 0.0, 0.0, 0, "INSUFFICIENT_DATA");
        }

        int totalRecords = records.size();
        int oreRecords = 0;
        int sharpTurns = 0;
        double totalDeviation = 0.0;
        
        // Vectors to analyze mining direction
        double prevDx = 0, prevDy = 0, prevDz = 0;
        boolean hasPrevVector = false;

        for (int i = 1; i < totalRecords; i++) {
            MiningRecord prev = records.get(i - 1);
            MiningRecord curr = records.get(i);

            // Count tracked ores
            if (curr.suspicionValue() > 0) {
                oreRecords++;
            }

            // Direction vector
            double dx = curr.x() - prev.x();
            double dy = curr.y() - prev.y();
            double dz = curr.z() - prev.z();
            double length = Math.sqrt(dx * dx + dy * dy + dz * dz);

            if (length > 0) {
                // Normalize
                dx /= length;
                dy /= length;
                dz /= length;

                if (hasPrevVector) {
                    // Dot product for direction change
                    double dot = dx * prevDx + dy * prevDy + dz * prevDz;
                    // Clamp for acos safety
                    dot = Math.max(-1.0, Math.min(1.0, dot));
                    double angle = Math.toDegrees(Math.acos(dot));

                    totalDeviation += angle;

                    // Sharp turn indicator (turns greater than 60 degrees)
                    if (angle > 60.0) {
                        sharpTurns++;
                    }
                }

                prevDx = dx;
                prevDy = dy;
                prevDz = dz;
                hasPrevVector = true;
            }
        }

        // 1. Ore Ratio Heuristic (Percentage of mined blocks that are target ores)
        double oreRatio = (double) oreRecords / totalRecords;

        // 2. Path Complexity Heuristic (Average deviation angle per block)
        double avgAngleDeviation = hasPrevVector ? (totalDeviation / (totalRecords - 1)) : 0.0;
        
        // Standard normal miners have path complexity under 15-20 degrees deviation
        // Xrayers navigating through caves/ore pockets have much higher complexities
        double pathComplexity = Math.min(100.0, (avgAngleDeviation / 45.0) * 100.0);

        // 3. Compute Xray Probability (0% - 100%)
        // High ore ratios and high complexity/sharp turns direct toward ores indicate xray.
        double probability = 0.0;
        
        // Base weight from ore ratio (target ratio > 15% is highly suspicious)
        probability += Math.min(50.0, (oreRatio / 0.15) * 50.0);

        // Turn rate weight (more sharp turns in small records count means erratic branch mining)
        double turnRate = (double) sharpTurns / totalRecords;
        probability += Math.min(30.0, (turnRate / 0.25) * 30.0);

        // Path complexity weight
        probability += Math.min(20.0, (pathComplexity / 80.0) * 20.0);

        // Cap probability
        probability = Math.max(0.0, Math.min(100.0, probability));

        // Suspect level classification
        String suspicionLevel = "CLEAN";
        if (probability >= 75.0) suspicionLevel = "CRITICAL";
        else if (probability >= 50.0) suspicionLevel = "HIGH";
        else if (probability >= 25.0) suspicionLevel = "LOW";

        return new AnalyticsResult(
            probability,
            oreRatio * 100.0,
            pathComplexity,
            sharpTurns,
            suspicionLevel
        );
    }
}
