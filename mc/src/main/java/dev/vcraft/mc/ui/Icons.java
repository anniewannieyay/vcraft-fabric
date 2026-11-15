package dev.vcraft.mc.ui;

import dev.vcraft.mc.Gfx;

/** 9x9 pixel icons. */
final class Icons {
    static final String[] MIC = {
            "...###...",
            "..#####..",
            "..#####..",
            "..#####..",
            ".#.###.#.",
            ".#.....#.",
            "..#####..",
            "....#....",
            "..#####.."};
    static final String[] HEADPHONES = {
            "..#####..",
            ".#.....#.",
            "#.......#",
            "#.......#",
            "##.....##",
            "###...###",
            "###...###",
            "##.....##",
            "........."};
    static final String[] GEAR = {
            "...#.#...",
            ".#.###.#.",
            "..#...#..",
            "##.....##",
            ".#..#..#.",
            "##.....##",
            "..#...#..",
            ".#.###.#.",
            "...#.#..."};
    static final String[] PHONE = {
            "##.......",
            "###......",
            "###......",
            ".##......",
            "..##.....",
            "...##.##.",
            "....#####",
            "......###",
            "........."};
    static final String[] HANGUP = {
            ".........",
            ".........",
            "..#####..",
            ".#######.",
            "###...###",
            "##.....##",
            ".........",
            ".........",
            "........."};
    static final String[] PLUS = {
            "....#....",
            "....#....",
            "....#....",
            "....#....",
            "#########",
            "....#....",
            "....#....",
            "....#....",
            "....#...."};
    static final String[] CLOSE = {
            "#.......#",
            ".#.....#.",
            "..#...#..",
            "...#.#...",
            "....#....",
            "...#.#...",
            "..#...#..",
            ".#.....#.",
            "#.......#"};
    static final String[] CHECK = {
            ".........",
            "........#",
            ".......#.",
            "......#..",
            "#....#...",
            ".#..#....",
            "..##.....",
            ".........",
            "........."};
    static final String[] REPLY = {
            "...#.....",
            "..##.....",
            ".#######.",
            "..##....#",
            "...#....#",
            "........#",
            "........#",
            ".........",
            "........."};
    static final String[] TRASH = {
            "...###...",
            "#########",
            ".#.....#.",
            ".#.#.#.#.",
            ".#.#.#.#.",
            ".#.#.#.#.",
            ".#.#.#.#.",
            ".#######.",
            "........."};
    static final String[] PEOPLE = {
            "...###...",
            "..#...#..",
            "..#...#..",
            "...###...",
            ".........",
            ".#######.",
            "#.......#",
            "#.......#",
            "#########"};
    static final String[] SERVER = {
            "#########",
            "#.......#",
            "#.##..#.#",
            "#########",
            "#.......#",
            "#.##..#.#",
            "#########",
            ".........",
            "........."};
    static final String[] GLOBE = {
            "..#####..",
            ".#..#..#.",
            "#..#.#..#",
            "#########",
            "#..#.#..#",
            "#########",
            "#..#.#..#",
            ".#..#..#.",
            "..#####.."};
    static final String[] REFRESH = {
            "...####.#",
            "..#....##",
            ".#....###",
            "#........",
            "#........",
            "#.......#",
            ".#.....#.",
            "..#####..",
            "........."};
    static final String[] NOTE = {
            "...######",
            "...######",
            "...#....#",
            "...#....#",
            "...#....#",
            ".###..###",
            "####.####",
            "####.####",
            ".##...##."};
    static final String[] HEART = {
            ".........",
            ".##...##.",
            "####.####",
            "#########",
            "#########",
            ".#######.",
            "..#####..",
            "...###...",
            "....#...."};
    static final String[] LOCK = {
            "..#####..",
            ".#.....#.",
            ".#.....#.",
            "#########",
            "####.####",
            "####.####",
            "#########",
            "#########",
            "........."};
    static final String[] DOTS = {
            ".........",
            ".........",
            ".........",
            ".........",
            "##..#..##",
            "##..#..##",
            ".........",
            ".........",
            "........."};
    static final String[] SLASH = {
            "#........",
            ".#.......",
            "..#......",
            "...#.....",
            "....#....",
            ".....#...",
            "......#..",
            ".......#.",
            "........#"};
    static final String[] LEFT = {
            ".....#...",
            "....#....",
            "...#.....",
            "..#......",
            ".#.......",
            "..#......",
            "...#.....",
            "....#....",
            ".....#..."};
    static final String[] RIGHT = {
            "...#.....",
            "....#....",
            ".....#...",
            "......#..",
            ".......#.",
            "......#..",
            ".....#...",
            "....#....",
            "...#....."};
    static final String[] PLAY = {
            ".#.......",
            ".###.....",
            ".#####...",
            ".#######.",
            ".#######.",
            ".#####...",
            ".###.....",
            ".#.......",
            "........."};
    static final String[] STOP = {
            ".........",
            ".#######.",
            ".#######.",
            ".#######.",
            ".#######.",
            ".#######.",
            ".#######.",
            ".#######.",
            "........."};

    // 16x16 pixel version of the VCraft logo. G = background gradient, F/M = face and mic, O = mic outline.
    private static final String[] LOGO = {
            "..GGGGGGGGGGGG..",
            ".GGGGGGGGGGGGGG.",
            "GGFFFFFFFFFFGGGG",
            "GGFFFFFFFFFFGGGG",
            "GGFGGFFFFGGFGGGG",
            "GGFGGFFFFGGFGGGG",
            "GGFFFFGGFFFFGGGG",
            "GGFFFGGGGFFFOOOG",
            "GGFFFGGGGFFOMMMO",
            "GGFFFGFFGFFOMMMO",
            "GGFFFFFFFFFOMMMO",
            "GGFFFFFFFFFOMMMO",
            "GGGGGGGGGGGGOOOG",
            "GGGGGGGGGGGGGMGG",
            ".GGGGGGGGGGGMMM.",
            "..GGGGGGGGGGGG.."};

    static void drawLogo(Gfx g, int x, int y) {
        for (int row = 0; row < 16; row++) {
            float t = row / 15f;
            int bg = 0xFF000000 | ((int) (0x52 + (0x1E - 0x52) * t) << 16) | ((int) (0xF2 + (0x8B - 0xF2) * t) << 8) | (int) (0x1A + (0x2B - 0x1A) * t);
            String line = LOGO[row];
            for (int col = 0; col < 16; col++) {
                int color = switch (line.charAt(col)) {
                    case 'G' -> bg;
                    case 'F', 'M' -> 0xFFD3EFC8;
                    case 'O' -> 0xFF17621F;
                    default -> 0;
                };
                if (color != 0) g.fill(x + col, y + row, 1, 1, color);
            }
        }
    }

    static void draw(Gfx g, String[] icon, int x, int y, int color) {
        for (int row = 0; row < icon.length; row++) {
            String line = icon[row];
            int start = -1;
            for (int col = 0; col <= line.length(); col++) {
                boolean on = col < line.length() && line.charAt(col) == '#';
                if (on && start < 0) start = col;
                if (!on && start >= 0) {
                    g.fill(x + start, y + row, col - start, 1, color);
                    start = -1;
                }
            }
        }
    }

    static void drawStruck(Gfx g, String[] icon, int x, int y, int color) {
        draw(g, icon, x, y, color);
        draw(g, SLASH, x, y, 0xFFED4245);
    }

    private Icons() {
    }
}
