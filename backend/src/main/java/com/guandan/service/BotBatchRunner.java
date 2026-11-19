package com.guandan.service;

import com.guandan.bots.BotSeeds;
import com.guandan.domain.model.Action;
import com.guandan.domain.model.DomainEvent;
import com.guandan.domain.model.EngineOutcome;
import com.guandan.domain.model.GameRuleException;
import com.guandan.domain.model.GameState;
import com.guandan.domain.model.Phase;
import com.guandan.domain.model.PlayerView;
import com.guandan.engine.GameEngine;
import com.guandan.engine.PlayerViewFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/** 在私有工作状态上执行"真人动作 + 机器人批次"；不触碰 GameRecord、不持锁，供同步与异步路径共用。 */
final class BotBatchRunner {

    static final int MAX_BOT_STEPS = 512;
    private static final int HUMAN_SEAT = 0;

    record BatchResult(GameState state, long acceptedVersion, List<SequencedEvent> events, long nextSeq) {
        BatchResult {
            events = List.copyOf(events);
        }
    }

    private final BotPolicy policy;
    private final ActionAdvancer advancer;

    BotBatchRunner(BotPolicy policy, ActionAdvancer advancer) {
        this.policy = policy;
        this.advancer = advancer;
    }

    BatchResult run(GameState initial, Action humanAction, long gameSeed, long startSeq) {
        List<SequencedEvent> newEvents = new ArrayList<>();
        long seq = startSeq;
        EngineOutcome humanOut = advancer.applyAction(initial, HUMAN_SEAT, humanAction);
        seq = appendEvents(newEvents, seq, humanOut);
        GameState work = humanOut.state();
        long acceptedVersion = work.version();

        int botSteps = 0;
        while (work.phase() == Phase.PLAYING) {
            Integer cp = work.currentPlayer();
            if (cp == null) {
                break;
            }
            if (cp == HUMAN_SEAT && !work.hands().get(HUMAN_SEAT).isEmpty()) {
                break;
            }
            if (botSteps >= MAX_BOT_STEPS) {
                throw new GameRuleException("BOT_STEP_LIMIT",
                        "机器人步数超过上限 " + MAX_BOT_STEPS, Map.of("limit", MAX_BOT_STEPS));
            }
            int seat = cp;
            List<Action> legal = GameEngine.getLegalActions(work, seat);
            PlayerView view = PlayerViewFactory.getPlayerView(work, seat);
            long botSeed = BotSeeds.derive(gameSeed, work.version(), seat);
            Action botAction = policy.chooseAction(view, legal, new SplittableRandom(botSeed));
            EngineOutcome botOut = advancer.applyAction(work, seat, botAction);
            seq = appendEvents(newEvents, seq, botOut);
            work = botOut.state();
            botSteps++;
        }
        return new BatchResult(work, acceptedVersion, newEvents, seq);
    }

    private static long appendEvents(List<SequencedEvent> sink, long seq, EngineOutcome out) {
        long stateVersion = out.state().version();
        for (DomainEvent e : out.events()) {
            sink.add(new SequencedEvent(seq++, stateVersion, e));
        }
        return seq;
    }
}
