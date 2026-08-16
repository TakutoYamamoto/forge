package forge.ai;

import java.util.Collections;
import java.util.List;

import com.google.common.collect.Lists;

import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.player.PlayerController;
import forge.game.player.RegisteredPlayer;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

/**
 * Covers the AI profile -> simulation mode wiring, which is what lets headless runs pick the
 * simulation AI through "sim -a &lt;profile&gt;" instead of only from the GUI lobby.
 */
public class AiProfileSimulationModeTest extends AITest {

    @Test
    public void testDefaultProfileUsesPlainHeuristics() {
        AiController ai = createAiWithProfile("Default", null);
        AssertJUnit.assertFalse(ai.usesFullSimulation());
        AssertJUnit.assertFalse(ai.usesHybridSimulation());
    }

    @Test
    public void testFullSimulationProfile() {
        AiController ai = createAiWithProfile("SimulationFull", null);
        AssertJUnit.assertTrue(ai.usesFullSimulation());
        AssertJUnit.assertFalse(ai.usesHybridSimulation());
    }

    @Test
    public void testHybridSimulationProfile() {
        AiController ai = createAiWithProfile("SimulationHybrid", null);
        AssertJUnit.assertTrue(ai.usesHybridSimulation());
        AssertJUnit.assertFalse(ai.usesFullSimulation());
    }

    @Test
    public void testExplicitOptionStillWinsOverProfile() {
        AiController ai = createAiWithProfile("Default", AIOption.USE_FULL_SIMULATION);
        AssertJUnit.assertTrue(ai.usesFullSimulation());
    }

    /**
     * The simulation profiles only exist to isolate the simulation setting, so any other difference
     * against Default would silently skew a comparison run.
     */
    @Test
    public void testSimulationProfilesOnlyDifferInSimulationMode() {
        LobbyPlayerAi defaultPlayer = new LobbyPlayerAi("default", null);
        defaultPlayer.setAiProfile("Default");

        for (String profileName : new String[] { "SimulationFull", "SimulationHybrid" }) {
            LobbyPlayerAi simPlayer = new LobbyPlayerAi(profileName, null);
            simPlayer.setAiProfile(profileName);

            for (AiProps prop : AiProps.values()) {
                if (prop == AiProps.SIMULATION_MODE) {
                    continue;
                }
                AssertJUnit.assertEquals(profileName + " differs from Default in " + prop,
                        AiProfileUtil.getAIProp(defaultPlayer, prop), AiProfileUtil.getAIProp(simPlayer, prop));
            }
        }
    }

    private AiController createAiWithProfile(final String profileName, final AIOption option) {
        LobbyPlayerAi lobbyPlayer = new LobbyPlayerAi("ai", option == null ? null : Collections.singleton(option));
        lobbyPlayer.setAiProfile(profileName);

        List<RegisteredPlayer> players = Lists.newArrayList();
        players.add(new RegisteredPlayer(new Deck()).setPlayer(new LobbyPlayerAi("opponent", null)));
        players.add(new RegisteredPlayer(new Deck()).setPlayer(lobbyPlayer));
        GameRules rules = new GameRules(GameType.Constructed);
        Match match = new Match(rules, players, "Test");
        Game game = new Game(players, rules, match);

        PlayerController controller = game.getPlayers().get(1).getController();
        return ((PlayerControllerAi) controller).getAi();
    }
}
