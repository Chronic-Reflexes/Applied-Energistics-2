/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2013 - 2014, AlgorithmX2, All rights reserved.
 *
 * Applied Energistics 2 is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Applied Energistics 2 is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Applied Energistics 2.  If not, see <http://www.gnu.org/licenses/lgpl>.
 */

package appeng.crafting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.google.common.base.Stopwatch;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

import net.minecraft.world.level.Level;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.execution.InputTemplate;
import appeng.core.AELog;
import appeng.crafting.inv.ChildCraftingSimulationState;
import appeng.crafting.inv.CraftingSimulationState;
import appeng.crafting.inv.NetworkCraftingSimulationState;
import appeng.hooks.ticking.TickHandler;
import appeng.me.service.CraftingService;

public class CraftingCalculation {
    private final NetworkCraftingSimulationState networkInv;
    private final Level level;
    private final KeyCounter missing = new KeyCounter();
    private final Object monitor = new Object();
    private final Stopwatch watch = Stopwatch.createUnstarted();
    private final CraftingTreeNode tree;
    private final AEKey output;
    // The initially requested amount of "output", may be reduced depending on the strategy used
    private final long requestedAmount;
    private final CalculationStrategy strategy;
    private boolean simulate = false;
    final ICraftingSimulationRequester simRequester;
    @Nullable
    private final CraftingService craftingService;
    private final boolean blockServerTick;
    private boolean running = false;
    private boolean done = false;
    /**
     * True while this calculation has been granted a slice and has not yet paused or finished. Guarded by
     * {@link #monitor}.
     */
    private boolean sliceOpen = false;
    /**
     * Set on the calculator thread before a background plan walks the tree. The root node is built earlier, on the
     * server thread, and keeps using the live crafting service.
     */
    private volatile boolean resolvesOffThread = false;
    private SnapshotQuery snapshotQuery = SnapshotQuery.NONE;
    private AEKey queryKey;
    private IPatternDetails.IInput queryInput;
    private List<InputTemplate> queryTemplates;
    private boolean queryReady = true;
    private Object queryResult;
    private Throwable queryError;
    private final Map<AEKey, Boolean> emitAnswers = new HashMap<>();
    private final Map<AEKey, List<IPatternDetails>> patternAnswers = new HashMap<>();
    private final Map<IPatternDetails.IInput, Map<AEKey, Boolean>> validityAnswers = new IdentityHashMap<>();
    private int time = 5;
    private int incTime = Integer.MAX_VALUE;
    private final List<CraftAttempt> attempts = AELog.isCraftingLogEnabled() ? new ArrayList<>() : null;

    public CraftingCalculation(Level level, IGrid grid, ICraftingSimulationRequester simRequester,
            GenericStack output, CalculationStrategy strategy) {
        this.level = level;
        this.output = output.what();
        this.requestedAmount = output.amount();
        this.strategy = strategy;
        this.simRequester = simRequester;
        this.blockServerTick = simRequester.blocksServerTick();
        this.craftingService = grid.getCraftingService() instanceof CraftingService service ? service : null;

        var storage = grid.getStorageService();
        var craftingService = grid.getCraftingService();
        this.networkInv = new NetworkCraftingSimulationState(storage, simRequester.getActionSource());

        try {
            this.tree = new CraftingTreeNode(craftingService, this, this.output, 1, null, -1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    void addMissing(AEKey what, long amount) {
        missing.add(what, amount);
    }

    public ICraftingPlan run() {
        try {
            TickHandler.instance().registerCraftingSimulation(this.level, this);
            this.resolvesOffThread = !this.blockServerTick;
            this.handlePausing();

            var plan = computePlan();
            this.logCraftingJob(plan);
            return plan;
        } catch (Exception ex) {
            AELog.info(ex, "Exception during crafting calculation.");
            throw new RuntimeException(ex);
        } finally {
            this.finish();
        }
    }

    private ICraftingPlan computePlan() throws InterruptedException {
        var fullAmountPlan = runCraftAttempt(false, requestedAmount);
        if (fullAmountPlan != null) {
            // Success with full amount!
            return fullAmountPlan;
        }

        if (strategy == CalculationStrategy.CRAFT_LESS) {
            // Try crafting less if possible using binary search.
            long successfulAmount = 0;
            ICraftingPlan successfulPlan = null;
            for (long increment = Long.highestOneBit(requestedAmount); increment > 0; increment /= 2) {
                long testAmount = successfulAmount + increment;
                if (testAmount < requestedAmount) {
                    var plan = runCraftAttempt(false, testAmount);
                    if (plan != null) {
                        // Success! :)
                        successfulAmount = testAmount;
                        successfulPlan = plan;
                    }
                }
            }

            // Found a successful plan! :)
            if (successfulPlan != null) {
                return successfulPlan;
            }
        }

        // Couldn't find a successful plan -> simulate.
        return runCraftAttempt(true, requestedAmount);
    }

    /**
     * @return null on failure
     */
    @Nullable
    @Contract("true, _ -> !null") // the calculation can't fail if simulated
    private CraftingPlan runCraftAttempt(boolean simulate, long amount) throws InterruptedException {
        this.simulate = simulate;

        final Stopwatch timer = Stopwatch.createStarted();

        ChildCraftingSimulationState craftingInventory = new ChildCraftingSimulationState(networkInv);
        craftingInventory.ignore(this.output);

        // Do the crafting. Throws in case of failure.
        try {
            this.tree.request(craftingInventory, amount, null);
        } catch (CraftBranchFailure failure) {
            if (AELog.isCraftingLogEnabled()) {
                this.attempts.add(new CraftAttempt(amount + " failed", timer));
            }
            return null;
        }
        // Add bytes for the tree size.
        craftingInventory.addBytes(this.tree.getNodeCount() * 8);

        // TODO: log tree?
        // for (String s : this.opsAndMultiplier.keySet()) {
        // final TwoIntegers ti = this.opsAndMultiplier.get(s);
        // AELog.crafting(s + " * " + ti.times + " = " + ti.perOp * ti.times);
        // }

        var plan = CraftingSimulationState.buildCraftingPlan(craftingInventory, this, amount);
        if (AELog.isCraftingLogEnabled()) {
            String type = simulate ? "simulated" : "succeeded";
            this.attempts.add(new CraftAttempt("%d %s (%d bytes)".formatted(amount, type, plan.bytes()), timer));
        }
        return plan;
    }

    void handlePausing() throws InterruptedException {
        if (this.incTime > 100) {
            this.incTime = 0;

            synchronized (this.monitor) {
                if (this.watch.elapsed(TimeUnit.MICROSECONDS) > this.time) {
                    this.running = false;
                    this.watch.stop();
                    this.closeSlice();
                    this.monitor.notify();
                }

                if (!this.running) {
                    AELog.craftingDebug("crafting job will now sleep");

                    while (!this.running) {
                        this.monitor.wait();
                    }

                    AELog.craftingDebug("crafting job now active");
                }
            }

            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
        }
        this.incTime++;
    }

    private void finish() {
        synchronized (this.monitor) {
            this.running = false;
            this.done = true;
            this.closeSlice();
            this.monitor.notify();
        }
    }

    public boolean blocksServerTick() {
        return this.blockServerTick;
    }

    public boolean isSimulation() {
        return this.simulate;
    }

    public AEKey getOutput() {
        return output;
    }

    public KeyCounter getMissingItems() {
        return missing;
    }

    Level getLevel() {
        return this.level;
    }

    /**
     * Returns true if this needs more simulation.
     * <p>
     * Grants a time slice and, for calculations that {@link #blocksServerTick() block the server tick}, waits until the
     * slice is spent. Background calculations use {@link #grantSimulationTime(int)} directly.
     *
     * @param micros microseconds of simulation
     * @return true if this needs more simulation
     */
    public boolean simulateFor(int micros) {
        if (!this.grantSimulationTime(micros)) {
            return false;
        }

        synchronized (this.monitor) {
            AELog.craftingDebug("main thread is now going to sleep");

            while (this.running) {
                try {
                    this.monitor.wait();
                } catch (InterruptedException ignored) {
                }
            }

            AELog.craftingDebug("main thread is now active");
        }

        return true;
    }

    /**
     * Grants a time slice and returns without waiting for the calculator thread to spend it.
     * <p>
     * A slice that is already running is left alone, so a later tick cannot extend it. Returns false when the
     * calculation is finished and the caller should drop it.
     *
     * @param micros microseconds of simulation
     * @return true if this needs more simulation
     */
    public boolean grantSimulationTime(int micros) {
        // Answer while the calculator is paused, then publish the result. Flushing first lets that answer see pattern
        // updates that were queued during the previous slice.
        this.serviceSnapshotQuery();

        synchronized (this.monitor) {
            if (this.done) {
                return false;
            }

            if (this.running) {
                return true;
            }
        }

        if (this.craftingService != null) {
            this.craftingService.flushDeferredCraftingProviderUpdates();
        }

        synchronized (this.monitor) {
            if (this.done) {
                return false;
            }

            if (this.running) {
                return true;
            }

            this.time = micros;
            if (this.watch.isRunning()) {
                this.watch.stop();
            }
            this.watch.reset();
            this.watch.start();
            this.running = true;
            this.openSlice();

            AELog.craftingDebug("crafting calculation granted a time slice");
            this.monitor.notify();
        }

        return true;
    }

    private void openSlice() {
        if (this.sliceOpen || this.craftingService == null) {
            return;
        }
        this.sliceOpen = true;
        this.craftingService.beginCalculationSlice();
    }

    private void closeSlice() {
        if (!this.sliceOpen || this.craftingService == null) {
            return;
        }
        this.sliceOpen = false;
        this.craftingService.endCalculationSlice();
    }

    private void logCraftingJob(ICraftingPlan plan) {
        if (AELog.isCraftingLogEnabled()) {
            ;
            var actionSource = this.simRequester.getActionSource();
            String actionSourceName;

            if (actionSource != null && actionSource.player().isPresent()) {
                var player = actionSource.player().get();
                actionSourceName = player.toString();
            } else if (actionSource != null && actionSource.machine().isPresent()) {
                var machineSource = actionSource.machine().get();
                var actionableNode = machineSource.getActionableNode();
                actionSourceName = actionableNode != null ? actionableNode.toString() : machineSource.toString();
            } else {
                actionSourceName = "[unknown source]";
            }

            StringBuilder message = new StringBuilder();
            message.append("CraftingCalculation issued by %s requesting [%dx%s] breakdown:\n".formatted(
                    actionSourceName, this.requestedAmount, this.output));
            for (var attempt : this.attempts) {
                message.append(" - %s in %d ms\n".formatted(
                        attempt.description, attempt.stopwatch.elapsed(TimeUnit.MILLISECONDS)));
            }
            message.append(" - final plan: %d (%d bytes)".formatted(plan.finalOutput().amount(), plan.bytes()));

            AELog.crafting(message.toString());
        }
    }

    public boolean hasMultiplePaths() {
        return this.tree.hasMultiplePaths();
    }

    boolean resolvesOffThread() {
        return this.resolvesOffThread;
    }

    @Nullable
    ICraftingService liveCraftingService() {
        return this.craftingService;
    }

    boolean canEmit(AEKey key) throws InterruptedException {
        var cached = this.emitAnswers.get(key);
        if (cached != null) {
            return cached;
        }

        var answer = (PatternLookup) this.awaitQuery(SnapshotQuery.PATTERNS, key, null, null);
        this.storeLookup(answer);
        return Boolean.TRUE.equals(this.emitAnswers.get(key));
    }

    List<IPatternDetails> patternsFor(AEKey key) throws InterruptedException {
        var cached = this.patternAnswers.get(key);
        if (cached != null) {
            return cached;
        }

        var answer = (PatternLookup) this.awaitQuery(SnapshotQuery.PATTERNS, key, null, null);
        this.storeLookup(answer);
        cached = this.patternAnswers.get(key);
        return cached != null ? cached : List.of();
    }

    @Nullable
    AEKey fuzzyCraftable(AEKey key, IPatternDetails.IInput input) throws InterruptedException {
        return (AEKey) this.awaitQuery(SnapshotQuery.FUZZY, key, input, null);
    }

    List<InputTemplate> validTemplates(IPatternDetails.IInput input, List<InputTemplate> templates)
            throws InterruptedException {
        var known = this.validityAnswers.computeIfAbsent(input, ignored -> new HashMap<>());
        var unknown = new ArrayList<InputTemplate>();
        for (var template : templates) {
            if (!known.containsKey(template.key())) {
                unknown.add(template);
            }
        }

        if (!unknown.isEmpty()) {
            var accepted = (List<?>) this.awaitQuery(SnapshotQuery.TEMPLATES, null, input, unknown);
            var acceptedKeys = new java.util.HashSet<AEKey>();
            for (var entry : accepted) {
                acceptedKeys.add(((InputTemplate) entry).key());
            }
            for (var template : unknown) {
                known.put(template.key(), acceptedKeys.contains(template.key()));
            }
        }

        var valid = new ArrayList<InputTemplate>();
        for (var template : templates) {
            if (Boolean.TRUE.equals(known.get(template.key()))) {
                valid.add(template);
            }
        }
        return valid;
    }

    private void storeLookup(PatternLookup answer) {
        this.emitAnswers.putAll(answer.emit);
        this.patternAnswers.putAll(answer.patternsByKey);
    }

    private Object awaitQuery(SnapshotQuery kind, @Nullable AEKey key, @Nullable IPatternDetails.IInput input,
            @Nullable List<InputTemplate> templates) throws InterruptedException {
        synchronized (this.monitor) {
            this.snapshotQuery = kind;
            this.queryKey = key;
            this.queryInput = input;
            this.queryTemplates = templates;
            this.queryResult = null;
            this.queryError = null;
            this.queryReady = false;

            while (!this.queryReady) {
                this.monitor.wait();
            }

            if (this.queryError != null) {
                throw new RuntimeException(this.queryError);
            }
            return this.queryResult;
        }
    }

    /**
     * Fills one pending snapshot query. Called on the server thread. The calculator thread is waiting, so this is the
     * only side that touches the live crafting service or pattern validity for a background plan.
     */
    private void serviceSnapshotQuery() {
        SnapshotQuery kind;
        AEKey key;
        IPatternDetails.IInput input;
        List<InputTemplate> templates;
        synchronized (this.monitor) {
            if (this.queryReady || this.snapshotQuery == SnapshotQuery.NONE) {
                return;
            }
            kind = this.snapshotQuery;
            key = this.queryKey;
            input = this.queryInput;
            templates = this.queryTemplates;
        }

        if (this.craftingService != null) {
            this.craftingService.flushDeferredCraftingProviderUpdates();
        }

        Object result = null;
        Throwable error = null;
        try {
            result = switch (kind) {
                case PATTERNS -> this.lookupPatterns(key);
                case FUZZY -> this.craftingService == null ? null
                        : this.craftingService.getFuzzyCraftable(key, candidate -> input.isValid(candidate, this.level));
                case TEMPLATES -> this.filterTemplates(input, templates);
                case NONE -> null;
            };
        } catch (Throwable t) {
            error = t;
        }

        synchronized (this.monitor) {
            if (this.snapshotQuery != kind || this.queryReady) {
                return;
            }
            this.queryResult = result;
            this.queryError = error;
            this.queryReady = true;
            this.snapshotQuery = SnapshotQuery.NONE;
            this.monitor.notifyAll();
        }
    }

    private PatternLookup lookupPatterns(AEKey key) {
        var emit = new HashMap<AEKey, Boolean>();
        var patternsByKey = new HashMap<AEKey, List<IPatternDetails>>();
        this.readPatternKey(key, emit, patternsByKey);

        var patterns = patternsByKey.getOrDefault(key, List.of());
        for (var details : patterns) {
            for (var patternInput : details.getInputs()) {
                var possible = patternInput.getPossibleInputs();
                if (possible.length == 0 || possible[0] == null) {
                    continue;
                }
                this.readPatternKey(possible[0].what(), emit, patternsByKey);
            }
        }
        return new PatternLookup(emit, patternsByKey);
    }

    private void readPatternKey(AEKey key, Map<AEKey, Boolean> emit, Map<AEKey, List<IPatternDetails>> patternsByKey) {
        if (patternsByKey.containsKey(key) || this.craftingService == null) {
            emit.putIfAbsent(key, false);
            patternsByKey.putIfAbsent(key, List.of());
            return;
        }

        emit.put(key, this.craftingService.canEmitFor(key));
        var patterns = List.copyOf(this.craftingService.getCraftingFor(key));
        for (var details : patterns) {
            for (var patternInput : details.getInputs()) {
                var possible = patternInput.getPossibleInputs();
                if (possible.length > 0 && possible[0] != null) {
                    patternInput.getRemainingKey(possible[0].what());
                }
            }
            details.getOutputs();
        }
        patternsByKey.put(key, patterns);
    }

    private List<InputTemplate> filterTemplates(@Nullable IPatternDetails.IInput input, List<InputTemplate> templates) {
        if (input == null || templates == null) {
            return List.of();
        }

        var accepted = new ArrayList<InputTemplate>();
        for (var template : templates) {
            if (input.isValid(template.key(), this.level)) {
                accepted.add(template);
            }
        }
        return List.copyOf(accepted);
    }

    private enum SnapshotQuery {
        NONE,
        PATTERNS,
        FUZZY,
        TEMPLATES
    }

    private record PatternLookup(Map<AEKey, Boolean> emit, Map<AEKey, List<IPatternDetails>> patternsByKey) {
    }

    private record CraftAttempt(String description, Stopwatch stopwatch) {
    }
}
