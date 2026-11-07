"""Writes res/values/strings.xml (English) and res/values-zh/strings.xml (Chinese) from one table, so both stay in
step. Copy follows the design package (content.rulesText / commonMistakes / coach / footnote / history / tone.examples)
and its tone (D28: short, spoken, no "first / second"); the Chinese name is 孔明棋, no Zhuge Liang story.
    python3 tools/strings.py"""
import os
from xml.sax.saxutils import escape

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
S = [
    # name, en, zh
    ("app_name", "Peg Solitaire", "孔明棋"),
    ("tab_play", "Game", "对局"),
    ("tab_rules", "Rules", "规则"),
    ("tab_settings", "Settings", "设置"),
    ("level_hint", "Look straight ahead", "平视前方"),
    ("open_panel", "Open panel", "打开面板"),
    ("plate_left", "%1$d left", "剩 %1$d"),
    # status (one sentence, design content.tone.examples)
    ("st_pick", "Tap a peg.", "点一颗子。"),
    ("st_tap_lit", "Tap a lit hole.", "点亮着的孔。"),
    ("st_hop", "Over it goes…", "跳过去…"),
    ("st_coach3", "The one you hopped leaves on its own.", "被跳的那颗自己走，你不用管。"),
    ("st_restored", "Picked up where you left off.", "接着上一盘。"),
    ("al_pick_first", "Tap a peg first.", "先点一颗子。"),
    ("al_cant_jump", "This one can't hop. No empty hole next to it.", "这颗跳不了，旁边没空槽。"),
    ("al_not_lit", "Hop over one peg. Tap a lit hole.", "隔一颗才能跳，点亮着的孔。"),
    ("al_nothing_to_undo", "Nothing to undo yet.", "还没跳。"),
    ("al_welcome_pick", "Read the rules, or tap a peg that can hop.", "先看规则，或点一颗能跳的子。"),
    ("res_full", "Nailed it. Last peg dead centre. %1$d moves.", "全解！正中那颗立住了。%1$d 步。"),
    ("res_full_18", "Nailed it. 18 moves, same as the 1912 record.", "全解！18 步，跟 1912 年的纪录一样。"),
    ("res_one", "One left, just off centre. So close.", "剩一颗，差一点点。"),
    ("res_stuck", "Stuck, %1$d left. Undo and try another way.", "卡住了，剩 %1$d 颗。悔一步试试别的。"),
    # play page
    ("count_line", "%1$d left · %2$d hops · %3$d moves", "剩 %1$d · 跳 %2$d · 步 %3$d"),
    ("rec_solved", "Solved %1$d× · best %2$d moves", "全解 %1$d 次 · 最少 %2$d 步"),
    ("rec_best_left", "Best: %1$d left", "最少剩 %1$d"),
    ("rec_none", "No record yet", "还没有记录"),
    ("rec_hinted", "Hint used: not a record", "用了提示，不计纪录"),
    ("starts_label", "Start", "起始图"),
    ("start_standard", "Standard", "标准"),
    ("start_diamond", "Diamond", "钻石"),
    ("start_plus", "Plus", "加号"),
    ("start_fireplace", "Fireplace", "壁炉"),
    ("start_pyramid", "Pyramid", "金字塔"),
    ("start_arrow", "Arrow", "箭头"),
    ("start_rec_solved", "Solved", "全解过"),
    ("start_rec_left", "Best %1$d", "最少 %1$d"),
    ("start_rec_none", "New", "没玩过"),
    ("btn_again", "Play again", "再来一盘"),
    ("btn_next_start", "Next start", "换个起始图"),
    ("btn_undo", "Undo", "悔一步"),
    ("hint_off", "Show a hint", "给个提示"),
    ("hint_on", "Hint on", "提示开着"),
    ("btn_mute", "Mute", "静音"),
    ("btn_unmute", "Unmute", "取消静音"),
    ("btn_restart", "Restart", "重开"),
    ("hint_sure", "This hop keeps a full solve.", "这一跳还能全解。"),
    ("hint_unsure", "Not sure it still solves. Try this one.", "不一定能全解，先这么跳。"),
    ("hint_impossible", "Full solve impossible from here.", "全解已不可能。"),
    ("hint_running", "Thinking…", "在算…"),
    ("hint_back", "Undo %1$d to a solvable spot", "悔 %1$d 步回到还能全解"),
    ("hint_idle", "Stuck? Turn on a hint.", "没头绪就开提示。"),
    ("hint_result", "Undo still works from here.", "结束了也能悔一步。"),
    ("coach_line", "First game %1$d/3 · %2$s", "第一盘 %1$d/3 · %2$s"),
    ("coach_1", "Tap a peg.", "点一颗子。"),
    ("coach_2", "Tap a lit hole.", "点亮着的孔。"),
    ("coach_3", "The one you hopped leaves on its own. Nothing to do.", "被跳的那颗自己走，你不用管。"),
    # confirmations (S13)
    ("confirm_restart", "Restart? This game has %1$d hops.", "重开？这盘跳了 %1$d 下。"),
    ("confirm_change", "Switch to %1$s? This game has %2$d hops.", "换成「%1$s」？这盘跳了 %2$d 下。"),
    ("confirm_lose_line", "This game won't be kept.", "这盘就不留了。"),
    ("btn_cancel", "Never mind", "算了"),
    ("confirm_yes_restart", "Restart", "重开"),
    ("confirm_yes_change", "Switch", "换"),
    ("confirm_exit", "Quit Peg Solitaire?", "退出孔明棋？"),
    ("confirm_exit_line", "Your game is saved. Pick it up next time.", "这盘会存好，下次接着玩。"),
    ("confirm_yes_exit", "Quit", "退出"),
    ("confirm_clear", "Clear all records?", "清除全部记录？"),
    ("confirm_clear_line", "Bests and solve counts of all six starts go. No undo.", "六个起始图的最好成绩和全解次数都清掉，不能撤回。"),
    ("confirm_yes_clear", "Clear", "清除"),
    ("lock_line", "The board waits while this is open.", "这里开着时，棋盘先停着。"),
    # rules (design content)
    ("rules_title", "Rules", "规则"),
    ("welcome_title", "Peg Solitaire", "孔明棋"),
    ("welcome_sub", "One player. Hop pegs until one is left.", "一个人玩。跳到只剩一颗。"),
    ("rules_1", "The middle hole starts empty.", "中间空一个孔。"),
    ("rules_2", "Hop a peg over its neighbour into an empty hole. Straight lines only, no diagonals.", "一颗子隔着旁边那颗，跳进空槽；横竖都行，斜的不行。"),
    ("rules_3", "The peg you hopped over comes out and goes to the tray.", "被跳过的那颗拔掉，飞进托盘。"),
    ("rules_4", "Keep going until you're stuck. Fewer left is better. Last peg dead centre is a full solve.", "跳到没得跳为止。剩得越少越好；最后一颗正好在正中，就是全解。"),
    ("rules_mistakes", "Easy mistakes", "新手常犯"),
    ("mistake_1", "No diagonals. Straight across only.", "斜着跳不行，只能横竖。"),
    ("mistake_2", "You can't hop over an empty hole. There has to be a peg in between.", "隔着空槽跳不了，中间得有一颗。"),
    ("mistake_3", "Don't chase pegs into the corners. Nobody can hop a corner peg later.", "别把子都赶到角上，角上的子最后没人能跳它。"),
    ("rules_source", "First printed in France in 1697 (Mercure galant). Hops by the same peg in a row count as one move; 18 moves is the fewest for a full solve (Bergholt, 1912).", "1697 年法国《Mercure galant》第一次印了这个玩法。同一颗子连着跳算一步，最少 18 步能全解（Bergholt，1912）。"),
    ("history_title", "Where it comes from", "来历"),
    ("history_show", "Show", "展开"),
    ("history_hide", "Hide", "收起"),
    ("history_1", "First printed in 1697 in the French magazine Mercure galant; the same year a portrait of the Princess of Soubise shows her with a board.", "1697 年法国杂志《Mercure galant》第一次印了这个玩法，同年苏比斯公主的肖像里就摆着一块。"),
    ("history_2", "Two boards exist: English 33-hole (this one) and European 37-hole.", "盘有两种：英式 33 孔（这块）和欧式 37 孔。"),
    ("history_3", "The Chinese names \"Kongming chess\" and \"solo diamond\" are modern; nothing to do with Zhuge Liang.", "中文叫「孔明棋」或「独立钻石棋」，都是近代的叫法，和诸葛亮没关系。"),
    ("history_4", "Bergholt found an 18-move solution in 1912; Beasley proved in 1964 that none is shorter.", "1912 年 Bergholt 找到 18 步的解，1964 年 Beasley 证明不能更少。"),
    ("diagram_caption", "Hop over one, it leaves", "隔一颗跳，被跳的走"),
    ("btn_start", "Start", "开始"),
    ("btn_back_game", "Back to the game", "回到对局"),
    # settings
    ("settings_title", "Settings", "设置"),
    ("settings_language", "Language", "语言"),
    ("lang_system", "System", "跟随系统"),
    ("lang_zh", "中文", "中文"),
    ("lang_en", "English", "English"),
    ("settings_sound", "Sound", "音效"),
    ("settings_volume", "Volume", "音量"),
    ("settings_reduced", "Reduce motion", "减少动态"),
    ("settings_tilt", "Board tilt", "棋盘倾角"),
    ("settings_coach", "First-game tips", "教练提示"),
    ("settings_reopen", "Reopen panel", "重开控制窗"),
    ("settings_clear", "Clear records", "清除记录"),
    ("settings_about_label", "About", "关于"),
    ("settings_about_text", "Version %1$s · sounds freesound / Kenney CC0", "版本 %1$s · 音效 freesound / Kenney CC0"),
    ("settings_exit", "Quit app", "退出应用"),
    ("opt_on", "On", "开"),
    ("opt_off", "Off", "关"),
]

def write(path, idx):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n')
        for row in S:
            v = escape(row[idx]).replace("'", "\\'").replace('"', '\\"')
            f.write('    <string name="%s">%s</string>\n' % (row[0], v))
        f.write("</resources>\n")


write(os.path.join(ROOT, "app/src/main/res/values/strings.xml"), 1)
write(os.path.join(ROOT, "app/src/main/res/values-zh/strings.xml"), 2)
print(len(S), "strings")
