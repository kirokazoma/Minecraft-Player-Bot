# Prediction-Based Speed Check Migration - Design

## Architecture Overview

```
CheckManager.onLivingUpdate()
    ↓
PlayerData (stores PredictionData)
    ↓
PredictionSpeedCheck.check(deltaX, deltaY, deltaZ)
    ↓
MovementPredictor.predictMovement()
    ↓
    ├─ generatePossibleVelocities()
    │   ├─ Base velocity (from last tick)
    │   ├─ + Knockback/Explosion
    │   ├─ + Environmental effects (slime, water, ice)
    │   ├─ + Jump possibilities
    │   ├─ + 0.03 movement variations
    │   ├─ + Player inputs (W/A/S/D × 9 combinations)
    │   └─ + Collision detection
    │
    ├─ findBestMatch(velocities, actualMovement)
    │   └─ Returns closest velocity by distance
    │
    └─ Returns offset (distance between predicted and actual)
```

## Component Design

### 1. CheckManager Integration

**Current State:**
```java
CheckSet {
    SpeedCheck speed;  // Threshold-based
}

// In onLivingUpdate():
if (checks.speed.check(deltaX, deltaZ)) {
    if (!inGrace && !inTpGrace) movementSetback = true;
}
```

**New State:**
```java
CheckSet {
    PredictionSpeedCheck predictionSpeed;  // Prediction-based
}

// In onLivingUpdate():
if (checks.predictionSpeed.check(deltaX, deltaY, deltaZ)) {
    if (!inGrace && !inTpGrace) movementSetback = true;
}
```

**Migration Strategy:**
- Keep SpeedCheck class for reference/rollback
- Add PredictionSpeedCheck to CheckSet
- Replace speed.check() call with predictionSpeed.check()
- Monitor for 1 week, then remove SpeedCheck if stable

### 2. PlayerData Integration

**Add PredictionData field:**
```java
public class PlayerData {
    // Existing fields...
    
    // Prediction state
    private final PredictionData predictionData;
    
    public PlayerData(ServerPlayer player) {
        // Existing initialization...
        this.predictionData = new PredictionData();
    }
    
    public PredictionData getPredictionData() {
        return predictionData;
    }
}
```

**Tick PredictionData in CheckManager:**
```java
// In onLivingUpdate(), before checks run:
data.getPredictionData().tick();
```

**Reset on setback/teleport:**
```java
// In executeSetback():
predictionData.clientVelocity = Vec3.ZERO;
predictionData.pendingKnockback = null;
predictionData.pendingExplosion = null;
```

### 3. Knockback/Explosion Tracking

**Hook LivingHurtEvent for knockback:**
```java
@SubscribeEvent
public void onLivingHurt(LivingHurtEvent event) {
    if (!(event.getEntity() instanceof ServerPlayer player)) return;
    
    PlayerData data = getPlayerData(player);
    if (data == null) return;
    
    // Check if damage source has knockback
    if (event.getSource().getDirectEntity() != null) {
        // Calculate knockback vector based on damage source
        Vec3 knockback = calculateKnockback(player, event.getSource());
        data.getPredictionData().applyKnockback(knockback);
    }
}
```

**Hook ExplosionEvent for explosions:**
```java
@SubscribeEvent
public void onExplosion(ExplosionEvent.Detonate event) {
    for (Entity entity : event.getAffectedEntities()) {
        if (!(entity instanceof ServerPlayer player)) continue;
        
        PlayerData data = getPlayerData(player);
        if (data == null) continue;
        
        // Calculate explosion velocity
        Vec3 explosionVec = calculateExplosionVelocity(
            player, event.getExplosion().getPosition()
        );
        data.getPredictionData().applyExplosion(explosionVec);
    }
}
```

### 4. PredictionSpeedCheck Modifications

**Current implementation is already solid, but needs:**

1. **Better offset reduction for network conditions:**
```java
private double reduceOffset(double offset) {
    // Network stability factor (from PlayerData)
    double networkFactor = playerData.getNetworkStabilityFactor();
    if (networkFactor > 1.0) {
        // Reduce offset proportionally to network instability
        // Factor 1.5 = reduce offset by 33%
        offset *= (2.0 / (networkFactor + 1.0));
    }
    
    // High ping (300ms+)
    int ping = getPlayerPing();
    if (ping > 300) {
        offset -= 0.01;
    }
    
    // Recent knockback/explosion
    if (predictionData.knockbackTicks < 5 || predictionData.explosionTicks < 5) {
        offset -= 0.02;
    }
    
    return Math.max(0, offset);
}
```

2. **Advantage decay tuning:**
```java
// Current: advantageGained *= 0.999 (decays slowly)
// Problem: Takes 693 ticks to decay from 1.0 to 0.5
// Solution: Faster decay for small advantages

if (advantageGained < 0.1) {
    advantageGained *= 0.95;  // Fast decay for small advantages
} else {
    advantageGained *= 0.999; // Slow decay for accumulated advantages
}
```

3. **VL weight scaling:**
```java
// Scale VL weight by offset magnitude
// Small offsets (0.005-0.01) = low weight
// Large offsets (0.1+) = high weight
double vlWeight = Math.min(10.0, Math.pow(offset / THRESHOLD, 2) * 2.0);

// During network instability, halve VL weight
if (isNetworkUnstable()) {
    vlWeight *= 0.5;
}
```

### 5. MovementPredictor Enhancements

**Current implementation is complete, but add:**

1. **Sprint-jump detection:**
```java
// In addJumpPossibilities():
if (player.isSprinting() && player.onGround()) {
    // Sprint-jump gives extra horizontal momentum
    Set<VectorData> sprintJumpVelocities = new HashSet<>();
    for (VectorData velocity : velocities) {
        Vec3 sprintJump = velocity.vector.add(0, getJumpPower(player), 0);
        // Add 30% horizontal boost for sprint-jump
        sprintJump = new Vec3(
            sprintJump.x * 1.3,
            sprintJump.y,
            sprintJump.z * 1.3
        );
        sprintJumpVelocities.add(new VectorData(sprintJump, VectorData.VectorType.JUMP, velocity));
    }
    velocities.addAll(sprintJumpVelocities);
}
```

2. **Ice momentum persistence:**
```java
// Already implemented in applyPlayerInputs():
if (data.getTicksSinceOnIce() < 5 && friction < 0.9) {
    friction = Math.max(friction, 0.89);
}
// This is correct - ice momentum carries for 5 ticks
```

3. **Velocity clamping for float precision:**
```java
// In applyEndOfTickPhysics():
// Clamp to float precision to match client
newX = (float) newX;
newY = (float) newY;
newZ = (float) newZ;
```

## Data Flow

### Normal Movement (No Cheat)
```
Tick N:
  Player at (0, 64, 0), sprinting forward
  clientVelocity = (0, 0, 0)
  
  MovementPredictor generates:
    - Normal walk: (0.13, 0, 0)
    - Sprint: (0.17, 0, 0)
    - Sprint + jump: (0.22, 0.42, 0)
    - ... (50+ more)
  
  Actual movement: (0.17, 0, 0)
  Best match: Sprint (0.17, 0, 0)
  Offset: 0.000 blocks
  
  Result: No flag, update clientVelocity = (0.17, 0, 0)

Tick N+1:
  Player jumps while sprinting
  clientVelocity = (0.17, 0, 0)
  
  MovementPredictor generates:
    - Continue sprint: (0.17, -0.08, 0)
    - Sprint + jump: (0.22, 0.42, 0)
    - ... (50+ more)
  
  Actual movement: (0.22, 0.42, 0)
  Best match: Sprint + jump (0.22, 0.42, 0)
  Offset: 0.000 blocks
  
  Result: No flag, update clientVelocity = (0.22, 0.42, 0)
```

### Speed Hack (2x Speed)
```
Tick N:
  Player at (0, 64, 0), using 2x speed hack
  clientVelocity = (0, 0, 0)
  
  MovementPredictor generates:
    - Normal walk: (0.13, 0, 0)
    - Sprint: (0.17, 0, 0)
    - Sprint + jump: (0.22, 0.42, 0)
    - ... (50+ more)
  
  Actual movement: (0.34, 0, 0)  // 2x sprint speed
  Best match: Sprint (0.17, 0, 0)
  Offset: 0.17 blocks  // Way over threshold!
  
  advantageGained += 0.17 = 0.17
  VL += 5.78 (offset * 5.0 * 2.0)
  
  Result: Flag, but no setback yet (advantage < 1.5)

Tick N+1:
  Player continues 2x speed
  clientVelocity = (0.17, 0, 0)  // Server thinks they're at normal speed
  
  Actual movement: (0.34, 0, 0)
  Best match: Sprint (0.17, 0, 0)
  Offset: 0.17 blocks
  
  advantageGained += 0.17 = 0.34
  VL += 5.78 = 11.56 total
  
  Result: Flag, no setback yet

Tick N+2:
  advantageGained += 0.17 = 0.51
  VL += 5.78 = 17.34 total
  
  Result: Flag, no setback yet

... continues until advantageGained >= 1.5 (9 ticks) or VL >= 50 (9 ticks)
Then: SETBACK + KICK
```

## Threshold Tuning

### Initial Values (Grim-inspired)
```java
THRESHOLD = 0.005;                    // 5mm - accumulate advantage
IMMEDIATE_SETBACK_THRESHOLD = 0.15;   // 15cm - instant setback
MAX_ADVANTAGE = 1.5;                  // blocks - setback trigger
ADVANTAGE_DECAY = 0.999;              // per tick when clean
```

### Tuning Process
1. **Week 1**: Monitor false positive rate
   - If FP rate > 0.01%: Increase THRESHOLD to 0.008
   - If FP rate > 0.05%: Increase THRESHOLD to 0.01
   
2. **Week 2**: Monitor detection time
   - If 2x speed takes >5 ticks: Decrease MAX_ADVANTAGE to 1.0
   - If 1.5x speed takes >10 ticks: Decrease MAX_ADVANTAGE to 1.0
   
3. **Week 3**: Monitor network tolerance
   - If high-ping players get flagged: Improve reduceOffset() logic
   - If jittery connections get flagged: Increase network factor scaling

### Expected Performance
- **False positive rate**: <0.01% (1 in 10,000 movements)
- **2x speed detection**: 2-3 ticks (100-150ms)
- **1.5x speed detection**: 4-5 ticks (200-250ms)
- **1.2x speed detection**: 8-10 ticks (400-500ms)

## Rollback Plan

If prediction-based check causes issues:

1. **Immediate rollback** (< 1 hour):
   ```java
   // In CheckSet constructor:
   this.speed = new SpeedCheck(data);  // Revert to threshold-based
   
   // In onLivingUpdate():
   if (checks.speed.check(deltaX, deltaZ)) {  // Revert call
   ```

2. **Hybrid mode** (1-7 days):
   ```java
   // Run both checks, only flag if BOTH agree
   boolean thresholdFlag = checks.speed.check(deltaX, deltaZ);
   boolean predictionFlag = checks.predictionSpeed.check(deltaX, deltaY, deltaZ);
   
   if (thresholdFlag && predictionFlag) {
       movementSetback = true;
   }
   ```

3. **Gradual rollout** (7-30 days):
   ```java
   // Use prediction for 50% of players (based on UUID hash)
   boolean usePrediction = (player.getUUID().hashCode() & 1) == 0;
   
   if (usePrediction) {
       if (checks.predictionSpeed.check(deltaX, deltaY, deltaZ)) {
           movementSetback = true;
       }
   } else {
       if (checks.speed.check(deltaX, deltaZ)) {
           movementSetback = true;
       }
   }
   ```

## Testing Strategy

### Unit Tests
1. **Sprint-jump burst**: Player sprints, jumps, should not flag
2. **Ice momentum**: Player leaves ice, momentum carries, should not flag
3. **Slime bounce**: Player bounces on slime, should not flag
4. **Knockback + sprint**: Player gets hit while sprinting, should not flag
5. **2x speed hack**: Player moves 2x speed, should flag within 3 ticks
6. **1.5x speed hack**: Player moves 1.5x speed, should flag within 5 ticks

### Integration Tests
1. **High ping (300ms)**: Legitimate player with 300ms ping, should not flag
2. **Jittery connection**: Player with 50-150ms jitter, should not flag
3. **Packet burst**: Player with packet batching (5 ticks of movement in 1 tick), should not flag
4. **Network spike**: Player with ping spike (50ms → 400ms → 50ms), should not flag

### Performance Tests
1. **CPU usage**: Measure CPU time per player per tick
2. **Memory usage**: Measure heap allocation per player
3. **GC pressure**: Measure GC frequency and duration
4. **Scalability**: Test with 100+ players moving simultaneously

## Correctness Properties

### Property 1: No false positives for legitimate movement
**Validates: Requirements 1.1, 1.2, 1.3, 1.4, 1.5**

For all legitimate movement patterns (sprint-jump, ice, slime, knockback):
- offset < THRESHOLD OR
- advantageGained < MAX_ADVANTAGE

**Test Strategy:**
- Generate 10,000 legitimate movement samples
- Run prediction engine on each
- Assert: No flags

### Property 2: Speed hacks detected within N ticks
**Validates: Requirements 2.1, 2.2, 2.3**

For speed multiplier M:
- 2x speed: detected within 3 ticks
- 1.5x speed: detected within 5 ticks
- 1.2x speed: detected within 10 ticks

**Test Strategy:**
- Simulate speed hack at various multipliers
- Count ticks until VL >= 50 or advantageGained >= MAX_ADVANTAGE
- Assert: Detection time <= expected

### Property 3: Network conditions don't cause false positives
**Validates: Requirements 2.4**

For all network conditions (ping, jitter, spikes):
- Legitimate movement with network issues: offset reduced by reduceOffset()
- Reduced offset < THRESHOLD OR advantageGained < MAX_ADVANTAGE

**Test Strategy:**
- Simulate legitimate movement with various network conditions
- Apply reduceOffset() logic
- Assert: No flags

### Property 4: Prediction engine is deterministic
**Validates: Requirements 3.1, 3.2**

For identical input state:
- generatePossibleVelocities() returns same set of velocities
- findBestMatch() returns same best match
- predictMovement() returns same offset

**Test Strategy:**
- Run prediction engine twice with identical input
- Assert: Results are identical

### Property 5: Memory usage is bounded
**Validates: Performance requirements**

For any player state:
- PredictionData size <= 1KB
- Velocity set size <= 500 vectors
- No memory leaks over 1000+ ticks

**Test Strategy:**
- Monitor heap allocation over 1000 ticks
- Assert: No unbounded growth

## Implementation Plan

### Phase 1: Integration (Day 1)
1. Add PredictionData to PlayerData
2. Wire PredictionSpeedCheck into CheckManager
3. Add knockback/explosion event hooks
4. Test basic functionality

### Phase 2: Tuning (Days 2-3)
1. Test with sprint-jump bursts
2. Test with ice momentum
3. Test with slime bouncing
4. Adjust thresholds based on results

### Phase 3: Network Testing (Days 4-5)
1. Test with high ping (300ms+)
2. Test with jittery connections
3. Test with packet bursts
4. Improve reduceOffset() logic

### Phase 4: Production Testing (Days 6-14)
1. Deploy to test server
2. Monitor false positive rate
3. Monitor detection time
4. Collect feedback from admins

### Phase 5: Rollout (Days 15-30)
1. Deploy to 50% of players
2. Monitor for issues
3. Deploy to 100% of players
4. Remove old SpeedCheck code
