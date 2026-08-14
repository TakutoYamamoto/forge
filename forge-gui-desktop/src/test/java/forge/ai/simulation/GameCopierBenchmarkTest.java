package forge.ai.simulation;

import forge.ai.AITest;
import forge.game.Game;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

import static org.testng.Assert.assertTrue;

/**
 * GameCopier の複製コストを測る。
 *
 * この数値が探索の計算予算になる。「1秒あたり何回複製できるか」が決まらないと
 * 探索の幅と深さを設計できない。単一の数値ではなく盤面規模との関係を見るのが目的で、
 * 探索が最も必要になるのは盤面が複雑な局面だから。
 *
 * makeCopy() と makeCopy(PhaseType, Player) の両方を測る。探索で実際に使うのは
 * フェーズを進める後者になる可能性が高い。
 *
 * 注意: GameCopier.PRUNE_HIDDEN_INFO は既定 false（＝全情報を忠実に複製）。
 * 有効化すると非公開カードがバニラのアーティファクトに差し替わり、コストが変わりうる。
 *
 * 出力のラベルはASCII。surefire経由だとコンソールのエンコーディング次第で
 * 日本語が化けて読めなくなるため。
 */
public class GameCopierBenchmarkTest extends AITest {

    /** 盤面規模の定義。lands/creatures/enchantments は各プレイヤーあたりの枚数。 */
    private record Board(String label, int lands, int creatures, int enchantments, int hand, int library) {
        int totalPermanents() {
            return 2 * (lands + creatures + enchantments);
        }

        /** 全ゾーン合計の枚数。makeCopy はこちらに追随する可能性があるため併記する。 */
        int totalCards() {
            return 2 * (lands + creatures + enchantments + hand + library);
        }
    }

    /**
     * 2軸を分離して測る。
     *
     * perm系: 手札とライブラリを固定してパーマネントだけ増やす。
     * lib系:  パーマネントを固定してライブラリだけ増やす。
     *
     * 混ぜると「パーマネントを増やしつつライブラリを減らす」形になり総枚数がほぼ一定になって、
     * どちらが効いているのか読めなくなる。
     */
    private static final int FIXED_HAND = 7;
    private static final int FIXED_LIBRARY = 40;
    private static final int FIXED_PERMS_PER_PLAYER = 10;

    private static final List<Board> BOARDS = List.of(
            new Board("perm-0",    0,  0, 0, FIXED_HAND, FIXED_LIBRARY),
            new Board("perm-8",    2,  2, 0, FIXED_HAND, FIXED_LIBRARY),
            new Board("perm-20",   5,  5, 0, FIXED_HAND, FIXED_LIBRARY),
            new Board("perm-40",  10, 10, 0, FIXED_HAND, FIXED_LIBRARY),
            new Board("perm-60",  12, 15, 3, FIXED_HAND, FIXED_LIBRARY),
            new Board("lib-0",     5,  5, 0, FIXED_HAND, 0),
            new Board("lib-80",    5,  5, 0, FIXED_HAND, 80));

    /** JITを踏ませるための空回し。全盤面ぶんを測定前に通す。 */
    private static final int WARMUP = 10;
    private static final int ITERATIONS = 20;
    /** GCなどの外乱を落とすため複数回測って最小値を採る。平均だと単発のGCで順序が入れ替わる。 */
    private static final int REPEATS = 2;
    /** 連続複製の持続スループットを見るための回数と、劣化を見るための区切り。 */
    private static final int SUSTAINED_COPIES = 1000;
    private static final int SUSTAINED_CHUNK = 200;

    @Test
    public void benchmarkCopyCost() {
        // 対局の構築が支配的に重いので、先に全部作ってから測る。
        List<Game> games = new ArrayList<>();
        for (Board board : BOARDS) {
            games.add(buildGame(board));
        }

        // 測定前に全盤面を空回しする。盤面ごとに warmup すると、最初に測る盤面だけが
        // JITコンパイルのコストを被り、盤面規模と実行時間の関係が壊れる。
        for (Game game : games) {
            Player ai = game.getPlayers().get(1);
            for (int i = 0; i < WARMUP; i++) {
                new GameCopier(game).makeCopy();
                new GameCopier(game).makeCopy(PhaseType.MAIN2, ai);
            }
        }

        System.out.println();
        System.out.println("=== GameCopier copy cost ===");
        System.out.printf("PRUNE_HIDDEN_INFO=false / warmup=%d, iterations=%d%n", WARMUP, ITERATIONS);
        System.out.println();
        System.out.printf("%-9s %6s %6s | %11s %8s | %11s %8s%n",
                "board", "perms", "cards", "makeCopy", "per sec", "toMain2", "per sec");
        System.out.println("-".repeat(72));

        double slowestRate = Double.MAX_VALUE;

        for (int i = 0; i < BOARDS.size(); i++) {
            Board board = BOARDS.get(i);
            Game game = games.get(i);
            Player ai = game.getPlayers().get(1);

            double plainMs = time(() -> new GameCopier(game).makeCopy());
            double phaseMs = time(() -> new GameCopier(game).makeCopy(PhaseType.MAIN2, ai));

            double plainRate = 1000.0 / plainMs;
            double phaseRate = 1000.0 / phaseMs;
            slowestRate = Math.min(slowestRate, Math.min(plainRate, phaseRate));

            System.out.printf("%-9s %6d %6d | %8.2f ms %8.0f | %8.2f ms %8.0f%n",
                    board.label(), board.totalPermanents(), board.totalCards(),
                    plainMs, plainRate, phaseMs, phaseRate);
        }

        System.out.println();
        // 最大盤面で測る。劣化が出るとすれば一番重い条件で出る。
        reportSustained(games.get(4), BOARDS.get(4).label());

        // 回帰検出用の下限。複製が壊滅的に遅くなったら探索設計が成立しないため気づけるようにする。
        // 実測値から十分に離してあり、マシン差で揺れる意図はない。
        assertTrue(slowestRate > 5.0,
                "GameCopier is far too slow (worst " + String.format("%.1f", slowestRate) + "/sec)");
    }

    /**
     * 連続複製したときの持続スループットとヒープの挙動。
     *
     * 上の表は「測定窓ごとの最小値」なので、複製を重ねたときの劣化が写らない。探索は複製を
     * 大量に積むため、実際の予算になるのはこちらの数値。
     */
    private void reportSustained(Game game, String label) {
        Player ai = game.getPlayers().get(1);
        Runtime rt = Runtime.getRuntime();

        System.gc();
        long heapBefore = rt.totalMemory() - rt.freeMemory();

        System.out.printf("=== sustained (toMain2, %s, %d copies) ===%n", label, SUSTAINED_COPIES);
        System.out.printf("%-12s %10s %10s%n", "chunk", "per sec", "heap MB");
        System.out.println("-".repeat(34));

        long start = System.nanoTime();
        long chunkStart = start;
        for (int i = 1; i <= SUSTAINED_COPIES; i++) {
            new GameCopier(game).makeCopy(PhaseType.MAIN2, ai);
            // 区切りごとの速度を出す。単調に落ちていくなら劣化、ばらつくだけならGCの揺れ。
            if (i % SUSTAINED_CHUNK == 0) {
                double chunkSec = (System.nanoTime() - chunkStart) / 1_000_000_000.0;
                System.out.printf("%-12s %10.0f %10d%n",
                        (i - SUSTAINED_CHUNK + 1) + "-" + i,
                        SUSTAINED_CHUNK / chunkSec,
                        (rt.totalMemory() - rt.freeMemory()) >> 20);
                chunkStart = System.nanoTime();
            }
        }
        double totalSec = (System.nanoTime() - start) / 1_000_000_000.0;

        long heapAfterRaw = rt.totalMemory() - rt.freeMemory();
        System.gc();
        long heapAfterGc = rt.totalMemory() - rt.freeMemory();

        System.out.println();
        System.out.printf("overall: %d copies in %.1f s  ->  %.0f per sec%n",
                SUSTAINED_COPIES, totalSec, SUSTAINED_COPIES / totalSec);
        System.out.printf("heap: before=%d MB  after=%d MB  after gc=%d MB  max=%d MB%n",
                heapBefore >> 20, heapAfterRaw >> 20, heapAfterGc >> 20, rt.maxMemory() >> 20);
        System.out.println();
    }

    /** ITERATIONS 回の平均を REPEATS 回とり、その最小値をミリ秒で返す。 */
    private double time(Runnable op) {
        double best = Double.MAX_VALUE;
        for (int r = 0; r < REPEATS; r++) {
            long start = System.nanoTime();
            for (int i = 0; i < ITERATIONS; i++) {
                op.run();
            }
            best = Math.min(best, (System.nanoTime() - start) / 1_000_000.0 / ITERATIONS);
        }
        return best;
    }

    /** 両プレイヤーに同じ盤面を作る。片側だけだと複製コストが実戦と乖離する。 */
    private Game buildGame(Board board) {
        Game game = initAndCreateGame();
        for (Player p : game.getPlayers()) {
            addCards("Forest", board.lands(), p);
            addCards("Runeclaw Bear", board.creatures(), p);
            // オーラではなく静的能力を持つエンチャント。オーラは対象に付いていないと
            // 状況起因処理で墓地に置かれ、意図した盤面規模にならない。
            addCards("Glorious Anthem", board.enchantments(), p);
            for (int i = 0; i < board.hand(); i++) {
                addCardToZone("Lightning Bolt", p, ZoneType.Hand);
            }
            fillLibrary(p, board.library());
        }
        // 静的能力を反映させてから測る（未解決の状態を複製すると実戦と条件が変わる）
        game.getAction().checkStateEffects(true);
        return game;
    }
}
