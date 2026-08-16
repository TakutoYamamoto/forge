package forge.ai;

import java.util.Set;

import forge.LobbyPlayer;
import forge.game.Game;
import forge.game.player.IGameEntitiesFactory;
import forge.game.player.Player;
import forge.game.player.PlayerController;
import org.tinylog.Logger;

public class LobbyPlayerAi extends LobbyPlayer implements IGameEntitiesFactory {

    private String aiProfile = "";
    private boolean rotateProfileEachGame;
    private AIOption option;

    public LobbyPlayerAi(String name, Set<AIOption> options) {
        super(name);
        if (options != null && !options.isEmpty()) {
            option = options.iterator().next();
        }
    }

    public void setAiProfile(String profileName) {
        Logger.debug("[AI Preferences] " + name + " using profile " + profileName);
        aiProfile = profileName;
    }
    public String getAiProfile() {
        return aiProfile;
    }

    public void setRotateProfileEachGame(boolean rotateProfileEachGame) {
        this.rotateProfileEachGame = rotateProfileEachGame;
    }

    private PlayerControllerAi createControllerFor(Player ai) {
        PlayerControllerAi result = new PlayerControllerAi(ai.getGame(), ai, this);
        result.getAi().setUseSimulation(option != null ? option : getSimulationModeFromProfile());
        return result;
    }

    /**
     * Simulation mode requested by the AI profile. Only consulted when no explicit option was
     * given at lobby creation, so it never overrides the GUI setting; it exists so that headless
     * runs (sim mode) can pick the mode through -a &lt;profile&gt;.
     */
    private AIOption getSimulationModeFromProfile() {
        String mode = AiProfileUtil.getAIProp(this, AiProps.SIMULATION_MODE);
        if (mode == null || mode.isEmpty() || mode.equalsIgnoreCase("none")) {
            return null;
        }
        if (mode.equalsIgnoreCase("hybrid")) {
            return AIOption.USE_HYBRID_SIMULATION;
        }
        if (mode.equalsIgnoreCase("full")) {
            return AIOption.USE_FULL_SIMULATION;
        }
        Logger.warn("[AI Preferences] " + name + ": unknown " + AiProps.SIMULATION_MODE + " value "
                + mode + " in profile " + aiProfile + ", using plain heuristics.");
        return null;
    }

    @Override
    public PlayerController createMindSlaveController(Player master, Player slave) {
        return createControllerFor(slave);
    }

    @Override
    public Player createIngamePlayer(Game game, final int id) {
        Player ai = new Player(getName(), game, id);
        ai.setFirstController(createControllerFor(ai));

        if (rotateProfileEachGame) {
            setAiProfile(AiProfileUtil.getRandomProfile());
        }
        return ai;
    }

    @Override
    public void hear(LobbyPlayer player, String message) { /* Local AI is deaf. */ }
}