# Prediction-Based Speed Check Migration

## Overview
Migrate from threshold-based SpeedCheck to prediction-based PredictionSpeedCheck to eliminate false positives from sprint-jump bursts and complex movement scenarios.

## Problem Statement
Current SpeedCheck uses threshold-based detection with generous multipliers to avoid false positives. This approach:
- Still produces false positives during sprint-jump bursts (player moving 2.8x calculated max speed)
- Requires constant tuning of multipliers for different scenarios
- Cannot accurately handle complex interactions (ice + sprint + jump + knockback)
- Lets real cheats slip through due to overly generous thresholds

Example false positive:
```
[AC-DEBUG] Gman | Speed | VL:9.09 | weight:9.09 | ping:85ms | 
buffer:90.9 excess:0.909 actual:1.694 max:0.604 ping:85ms
```
Player moving 1.694 blocks/tick vs calculated max of 0.604 blocks/tick (2.8x over).

## Solution
Use Grim-style prediction engine that:
1. Generates multiple possible velocity vectors (jumps, knockback, 0.03, environmental effects)
2. Finds closest match to actual movement
3. Flags based on offset (distance between predicted and actual)
4. Uses 0.005 block threshold (vs current ~0.6 block effective threshold)

## User Stories

### 1. As a server admin, I want legitimate players to never get flagged for speed
**Acceptance Criteria:**
- Sprint-jump bursts (up to 1.7 blocks/tick) do not trigger flags
- Ice momentum transitions do not trigger flags
- Slime block bouncing does not trigger flags
- Knockback + sprint combinations do not trigger flags
- 85ms ping players have same accuracy as 0ms ping players

### 2. As a server admin, I want real speed hackers caught within 2-3 ticks
**Acceptance Criteria:**
- 2x speed hack accumulates 50+ VL within 3 ticks
- 1.5x speed hack accumulates 50+ VL within 5 ticks
- Subtle speed hacks (1.2x) accumulate 50+ VL within 10 ticks
- No false positives from network conditions

### 3. As a developer, I want the prediction engine to be maintainable
**Acceptance Criteria:**
- MovementPredictor is isolated from check logic
- PredictionData tracks state separately from PlayerData
- Adding new movement types (riptide, elytra) requires only MovementPredictor changes
- Check thresholds are configurable

## Technical Requirements

### 1. Wire PredictionSpeedCheck into CheckManager
- Replace `SpeedCheck speed` with `PredictionSpeedCheck predictionSpeed` in CheckSet
- Call `predictionSpeed.check(deltaX, deltaY, deltaZ)` instead of `speed.check(deltaX, deltaZ)`
- Maintain backward compatibility with existing setback logic

### 2. Integrate PredictionData with PlayerData
- Add `PredictionData predictionData` field to PlayerData
- Initialize in PlayerData constructor
- Tick predictionData in CheckManager.onLivingUpdate()
- Reset predictionData on setback/teleport

### 3. Handle Knockback/Explosion Events
- Hook into LivingHurtEvent for knockback tracking
- Hook into ExplosionEvent for explosion velocity
- Apply velocities to PredictionData.pendingKnockback/pendingExplosion

### 4. Tune Thresholds
- Start with THRESHOLD = 0.005 (5mm)
- IMMEDIATE_SETBACK_THRESHOLD = 0.15 (15cm)
- MAX_ADVANTAGE = 1.5 blocks
- Monitor false positive rate and adjust

### 5. Logging and Debugging
- Log prediction type (NORMAL, JUMP, KNOCKBACK, etc.) on flags
- Log offset, advantage, actual vs predicted vectors
- Maintain existing [AC-DEBUG] format for compatibility

## Non-Functional Requirements

### Performance
- Prediction engine must run in <1ms per player per tick on modern hardware
- Memory overhead <1KB per player
- No GC pressure from velocity generation

### Compatibility
- Must work with Forge 1.20.1
- Must not break existing checks (Flight, NoFall, etc.)
- Must maintain existing setback behavior

### Testing
- Test with sprint-jump bursts on flat ground
- Test with ice momentum transitions
- Test with slime block bouncing
- Test with knockback + sprint combinations
- Test with 2x speed hack
- Test with 1.5x speed hack
- Test with subtle 1.2x speed hack

## Success Metrics
- False positive rate: <0.01% (1 in 10,000 movements)
- Detection time for 2x speed: <3 ticks
- Detection time for 1.5x speed: <5 ticks
- CPU usage: <5% increase vs current SpeedCheck

## Out of Scope
- Elytra movement prediction (future enhancement)
- Riptide trident prediction (future enhancement)
- Vehicle prediction improvements (separate checks handle this)
- Client-side prediction sync (server-side only)
