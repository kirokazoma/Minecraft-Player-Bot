# Prediction-Based Speed Check Migration - Tasks

## Phase 1: Core Integration

- [ ] 1. Add PredictionData to PlayerData
  - [ ] 1.1 Add `private final PredictionData predictionData` field to PlayerData
  - [ ] 1.2 Initialize in PlayerData constructor: `this.predictionData = new PredictionData()`
  - [ ] 1.3 Add getter: `public PredictionData getPredictionData()`
  - [ ] 1.4 Reset predictionData in executeSetback(): set clientVelocity to Vec3.ZERO, clear knockback/explosion

- [ ] 2. Wire PredictionSpeedCheck into CheckManager
  - [ ] 2.1 Replace `SpeedCheck speed` with `PredictionSpeedCheck predictionSpeed` in CheckSet constructor
  - [ ] 2.2 Update check call from `speed.check(deltaX, deltaZ)` to `predictionSpeed.check(deltaX, deltaY, deltaZ)`
  - [ ] 2.3 Tick predictionData before checks run: `data.getPredictionData().tick()`
  - [ ] 2.4 Keep old SpeedCheck class for reference (don't delete yet)

- [ ] 3. Add knockback tracking
  - [ ] 3.1 In CheckManager.onLivingHurt(), detect knockback from damage source
  - [ ] 3.2 Calculate knockback vector based on attacker position and damage
  - [ ] 3.3 Apply to PredictionData: `data.getPredictionData().applyKnockback(knockbackVec)`
  - [ ] 3.4 Test with player getting hit while moving

- [ ] 4. Add explosion tracking
  - [ ] 4.1 Subscribe to ExplosionEvent.Detonate in CheckManager
  - [ ] 4.2 For each affected player, calculate explosion velocity
  - [ ] 4.3 Apply to PredictionData: `data.getPredictionData().applyExplosion(explosionVec)`
  - [ ] 4.4 Test with TNT and creeper explosions

## Phase 2: Prediction Engine Enhancements

- [ ] 5. Add sprint-jump momentum boost
  - [ ] 5.1 In MovementPredictor.addJumpPossibilities(), detect sprint + jump
  - [ ] 5.2 Add 30% horizontal boost to sprint-jump velocities
  - [ ] 5.3 Test with player sprint-jumping on flat ground
  - [ ] 5.4 Verify offset < 0.005 for legitimate sprint-jumps

- [ ] 6. Improve offset reduction for network conditions
  - [ ] 6.1 In PredictionSpeedCheck.reduceOffset(), apply networkStabilityFactor
  - [ ] 6.2 Scale offset reduction: `offset *= (2.0 / (networkFactor + 1.0))`
  - [ ] 6.3 Test with simulated high ping (300ms+)
  - [ ] 6.4 Test with simulated jitter (50-150ms variance)

- [ ] 7. Tune advantage decay
  - [ ] 7.1 Implement fast decay for small advantages (<0.1): `advantageGained *= 0.95`
  - [ ] 7.2 Keep slow decay for large advantages (>=0.1): `advantageGained *= 0.999`
  - [ ] 7.3 Test decay rate with legitimate movement
  - [ ] 7.4 Verify advantage doesn't accumulate from false positives

- [ ] 8. Improve VL weight scaling
  - [ ] 8.1 Scale VL by offset magnitude: `vlWeight = min(10.0, pow(offset / THRESHOLD, 2) * 2.0)`
  - [ ] 8.2 Halve VL during network instability: `if (isNetworkUnstable()) vlWeight *= 0.5`
  - [ ] 8.3 Test VL accumulation rate for 2x speed hack
  - [ ] 8.4 Verify VL reaches 50 within 3 ticks for 2x speed

## Phase 3: Testing and Validation

- [ ] 9. Test legitimate movement patterns
  - [ ] 9.1 Sprint-jump bursts on flat ground (should not flag)
  - [ ] 9.2 Ice momentum transitions (should not flag)
  - [ ] 9.3 Slime block bouncing (should not flag)
  - [ ] 9.4 Knockback + sprint combinations (should not flag)
  - [ ] 9.5 Verify offset < 0.005 for all legitimate patterns

- [ ] 10. Test speed hack detection
  - [ ] 10.1 2x speed hack (should flag within 3 ticks)
  - [ ] 10.2 1.5x speed hack (should flag within 5 ticks)
  - [ ] 10.3 1.2x speed hack (should flag within 10 ticks)
  - [ ] 10.4 Verify VL accumulation rate matches expectations

- [ ] 11. Test network tolerance
  - [ ] 11.1 High ping (300ms) legitimate movement (should not flag)
  - [ ] 11.2 Jittery connection (50-150ms jitter) legitimate movement (should not flag)
  - [ ] 11.3 Packet burst (5 ticks in 1 tick) legitimate movement (should not flag)
  - [ ] 11.4 Ping spike (50ms → 400ms → 50ms) legitimate movement (should not flag)

- [ ] 12. Performance testing
  - [ ] 12.1 Measure CPU time per player per tick (should be <1ms)
  - [ ] 12.2 Measure memory usage per player (should be <1KB)
  - [ ] 12.3 Test with 100+ players moving simultaneously
  - [ ] 12.4 Monitor GC frequency and duration

## Phase 4: Threshold Tuning

- [ ] 13. Monitor false positive rate
  - [ ] 13.1 Deploy to test server with logging enabled
  - [ ] 13.2 Collect 10,000+ movement samples
  - [ ] 13.3 Calculate false positive rate (target: <0.01%)
  - [ ] 13.4 Adjust THRESHOLD if FP rate > 0.01%

- [ ] 14. Monitor detection time
  - [ ] 14.1 Test 2x speed hack detection time (target: <3 ticks)
  - [ ] 14.2 Test 1.5x speed hack detection time (target: <5 ticks)
  - [ ] 14.3 Test 1.2x speed hack detection time (target: <10 ticks)
  - [ ] 14.4 Adjust MAX_ADVANTAGE if detection time too slow

- [ ] 15. Fine-tune network tolerance
  - [ ] 15.1 Monitor flags for high-ping players
  - [ ] 15.2 Monitor flags for jittery connections
  - [ ] 15.3 Improve reduceOffset() logic if needed
  - [ ] 15.4 Verify network factor scaling is effective

## Phase 5: Production Rollout

- [ ] 16. Gradual rollout
  - [ ] 16.1 Deploy to 10% of players (UUID hash % 10 == 0)
  - [ ] 16.2 Monitor for 3 days, collect feedback
  - [ ] 16.3 Deploy to 50% of players if stable
  - [ ] 16.4 Monitor for 7 days, collect feedback

- [ ] 17. Full deployment
  - [ ] 17.1 Deploy to 100% of players
  - [ ] 17.2 Monitor for 14 days
  - [ ] 17.3 Remove old SpeedCheck code if stable
  - [ ] 17.4 Update documentation

## Phase 6: Documentation and Cleanup

- [ ] 18. Update documentation
  - [ ] 18.1 Document prediction engine architecture
  - [ ] 18.2 Document threshold tuning process
  - [ ] 18.3 Document rollback procedure
  - [ ] 18.4 Add troubleshooting guide

- [ ] 19. Code cleanup
  - [ ] 19.1 Remove old SpeedCheck class
  - [ ] 19.2 Remove debug logging
  - [ ] 19.3 Add final code comments
  - [ ] 19.4 Run final diagnostics check
