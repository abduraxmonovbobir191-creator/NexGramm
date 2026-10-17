package org.telegram.messenger;

import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// NexGramm: ✦ streaks — how many days in a row two people (or a group) keep talking. Worked out on this phone
// from the chat history only; nothing is sent anywhere, so it also works with friends on plain Telegram.
//  - a day counts in a private chat when both wrote that day; in a group when at least two people wrote;
//    channels, bots, Saved Messages and Telegram's service chat have none
//  - the ✦ shows from the 3rd day in a row and has no upper limit; one quiet day is forgiven, two quiet days
//    in a row end it (at the start of the second one it shows ⏳); talking again starts from zero
//  - kept for good: friends since (the first message), days talked in total, the record run, message count
//  - a broken run can be brought back within 5 days, and a run can be frozen for a trip (paid with ✦ Nex)
//  - the first time the app sees a chat it reads its recent history from Telegram once (paged, up to the
//    point the run started) so long streaks show at once; after that every new message updates it.
public class NexStreaks implements NotificationCenter.NotificationCenterDelegate {

    public static final int SHOW_FROM = 3;
    public static final int[] MILESTONES = {3, 7, 30, 100, 365};
    public static final int RESTORE_DAYS = 5;

    private static final int ME = 1, THEM = 2, TWO_SENDERS = 4, COUNTED = 8;
    private static final int BACKFILL_DIALOGS = 60;
    private static final int BACKFILL_MAX_PAGES = 20;
    private static final int BACKFILL_MAX_DAYS = 400;

    private static final ExecutorService DISK = Executors.newSingleThreadExecutor();
    private static final NexStreaks[] instances = new NexStreaks[UserConfig.MAX_ACCOUNT_COUNT];

    public static NexStreaks getInstance(int account) {
        NexStreaks s = instances[account];
        if (s == null) {
            synchronized (NexStreaks.class) {
                s = instances[account];
                if (s == null) {
                    instances[account] = s = new NexStreaks(account);
                }
            }
        }
        return s;
    }

    public static final class Streak {
        public long dialogId;
        public int count, start, last;        // the current run: mutual days, first and last of them
        public int lostCount, lostLast;       // the run that ended last (it can be brought back for a while)
        public int record, total, firstDay;   // longest run, days talked in total, first message's day
        public int frozenUntil;               // days up to this one never break the run
        public int messages;                  // messages in the chat (Telegram's count, then counted up)
        public int milestone;                 // the last milestone already offered as a story
        public boolean backfilled;
        final HashMap<Integer, long[]> pending = new HashMap<>(); // recent day -> {flags, first sender}

        public boolean visible() {
            return count >= SHOW_FROM;
        }
    }

    private final int account;
    private final HashMap<Long, Streak> streaks = new HashMap<>();
    private boolean started;
    private boolean saveQueued;
    private boolean backfilling;

    private NexStreaks(int account) {
        this.account = account;
    }

    // ---- days ----

    public static int today() {
        return day(System.currentTimeMillis() / 1000);
    }

    // the local calendar day of a unix time, so "a day" is the user's day
    public static int day(long unixSeconds) {
        final long local = unixSeconds + TimeZone.getDefault().getOffset(unixSeconds * 1000L) / 1000;
        long q = local / 86400L;
        if (local % 86400L < 0) {
            q--;
        }
        return (int) q;
    }

    // the unix time a local day starts
    public static long dayStart(int day) {
        long guess = day * 86400L;
        return guess - TimeZone.getDefault().getOffset(guess * 1000L) / 1000;
    }

    // ---- which chats have one ----

    public boolean eligible(long dialogId) {
        if (dialogId == 0 || DialogObject.isEncryptedDialog(dialogId)) {
            return false;
        }
        final MessagesController mc = MessagesController.getInstance(account);
        if (dialogId > 0) {
            if (dialogId == UserConfig.getInstance(account).getClientUserId() || UserObject.isService(dialogId)) {
                return false;
            }
            final TLRPC.User user = mc.getUser(dialogId);
            return user != null && !user.bot && !UserObject.isDeleted(user);
        }
        final TLRPC.Chat chat = mc.getChat(-dialogId);
        return chat != null && !(ChatObject.isChannel(chat) && !chat.megagroup) && !ChatObject.isNotInChat(chat);
    }

    // ---- start, new messages ----

    public void start() {
        if (started) {
            return;
        }
        started = true;
        load();
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.didReceiveNewMessages);
        AndroidUtilities.runOnUIThread(this::backfill, 15_000);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id != NotificationCenter.didReceiveNewMessages || args.length < 3 || (Boolean) args[2]
            || args.length > 3 && args[3] instanceof Integer && (Integer) args[3] != 0) {
            return; // scheduled and bot welcome messages are not talk
        }
        final long dialogId = (Long) args[0];
        @SuppressWarnings("unchecked") final ArrayList<MessageObject> messages = (ArrayList<MessageObject>) args[1];
        if (messages == null || !eligible(dialogId)) {
            return;
        }
        boolean changed = false;
        for (int i = 0; i < messages.size(); i++) {
            final MessageObject mo = messages.get(i);
            if (mo == null || mo.messageOwner == null || mo.messageOwner instanceof TLRPC.TL_messageService) {
                continue;
            }
            changed |= add(dialogId, mo.messageOwner.date, mo.isOut(), mo.getSenderId(), true);
        }
        if (changed) {
            NotificationCenter.getInstance(this.account).postNotificationName(NotificationCenter.dialogsNeedReload);
        }
    }

    // one message; true when the visible streak changed
    private boolean add(long dialogId, int date, boolean out, long sender, boolean fresh) {
        final int t = today();
        final int d = day(date);
        if (d < t - 3 || d > t + 1) {
            return false;
        }
        final Streak s = obtain(dialogId);
        if (fresh) {
            s.messages++;
        }
        if (s.firstDay == 0 || d < s.firstDay) {
            s.firstDay = d;
        }
        long[] p = s.pending.get(d);
        if (p == null) {
            s.pending.put(d, p = new long[2]);
        }
        if (dialogId < 0) {
            if (p[1] == 0) {
                p[1] = sender;
            } else if (p[1] != sender) {
                p[0] |= TWO_SENDERS;
            }
        } else {
            p[0] |= out ? ME : THEM;
        }
        final boolean mutual = dialogId < 0 ? (p[0] & TWO_SENDERS) != 0 : (p[0] & (ME | THEM)) == (ME | THEM);
        boolean changed = false;
        if (mutual && (p[0] & COUNTED) == 0) {
            p[0] |= COUNTED;
            final boolean was = s.visible();
            addMutual(s, d);
            changed = was != s.visible() || s.visible();
        }
        for (Iterator<Integer> it = s.pending.keySet().iterator(); it.hasNext(); ) {
            if (it.next() < t - 3) {
                it.remove();
            }
        }
        saveSoon();
        return changed;
    }

    private static void addMutual(Streak s, int d) {
        if (s.count > 0) {
            if (d <= s.last) {
                return;
            }
            final int ref = Math.max(s.last, s.frozenUntil);
            if (d - ref > 2) {
                s.lostCount = s.count;
                s.lostLast = s.last;
                s.count = 0;
            }
        }
        if (s.count == 0) {
            s.start = d;
        }
        s.count++;
        s.last = d;
        s.total++;
        s.record = Math.max(s.record, s.count);
    }

    private Streak obtain(long dialogId) {
        Streak s = streaks.get(dialogId);
        if (s == null) {
            s = new Streak();
            s.dialogId = dialogId;
            streaks.put(dialogId, s);
        }
        return s;
    }

    // ---- what the UI reads ----

    // the streak with today's verdict applied (a run two quiet days old ends here), or null
    public Streak get(long dialogId) {
        final Streak s = streaks.get(dialogId);
        if (s == null) {
            return null;
        }
        if (s.count > 0) {
            final int t = today();
            final int ref = Math.max(s.last, Math.min(s.frozenUntil, t));
            if (t - ref >= 3) {
                s.lostCount = s.count;
                s.lostLast = s.last;
                s.count = 0;
                saveSoon();
            }
        }
        return s;
    }

    // the run is alive but nobody (or only one side) wrote yesterday and today yet: it ends tonight
    public boolean atRisk(Streak s) {
        if (s == null || !s.visible()) {
            return false;
        }
        final int t = today();
        return t - Math.max(s.last, Math.min(s.frozenUntil, t)) == 2;
    }

    public boolean frozen(Streak s) {
        return s != null && s.frozenUntil >= today();
    }

    // the brand ✦ takes a color by level: 3+ soft violet, 7+ violet, 30+ magenta, 100+ gold, 365+ ✦✦ gold
    public static int levelColor(int count) {
        if (count >= 100) {
            return 0xFFF2B01E;
        } else if (count >= 30) {
            return 0xFFD02BF0;
        } else if (count >= 7) {
            return 0xFF8A4DFF;
        }
        return 0xFFA394FF;
    }

    public static String symbol(int count) {
        return count >= 365 ? "✦✦" : "✦";
    }

    // " ✦12" (with ⏳ or ❄ when it applies) to put after a chat's name, or null when there is none
    public CharSequence badge(long dialogId) {
        final Streak s = get(dialogId);
        if (s == null || !s.visible()) {
            return null;
        }
        final String text = "  " + symbol(s.count) + s.count + (atRisk(s) ? " ⏳" : frozen(s) ? " ❄" : "");
        final SpannableString out = new SpannableString(text);
        out.setSpan(new ForegroundColorSpan(levelColor(s.count)), 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return out;
    }

    // a milestone the chat just reached and has not offered as a story yet (0 if none); marks it offered
    public int takeMilestone(long dialogId) {
        final Streak s = get(dialogId);
        if (s == null || !s.visible()) {
            return 0;
        }
        int reached = 0;
        for (int m : MILESTONES) {
            if (s.count >= m) {
                reached = m;
            }
        }
        if (reached > s.milestone) {
            s.milestone = reached;
            saveSoon();
            return s.count == reached ? reached : 0;
        }
        return 0;
    }

    // ---- paid: bring back a run that ended in the last few days, or freeze one for a while ----

    public boolean canRestore(Streak s) {
        return s != null && s.count == 0 && s.lostCount >= SHOW_FROM && today() - s.lostLast <= RESTORE_DAYS;
    }

    public void restore(long dialogId) {
        final Streak s = get(dialogId);
        if (!canRestore(s)) {
            return;
        }
        s.count = s.lostCount;
        s.last = today() - 1; // as if yesterday counted: today keeps it going
        s.lostCount = 0;
        s.record = Math.max(s.record, s.count);
        saveSoon();
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload);
    }

    public void freeze(long dialogId, int days) {
        final Streak s = get(dialogId);
        if (s == null || !s.visible()) {
            return;
        }
        s.frozenUntil = Math.max(s.frozenUntil, today()) + days;
        saveSoon();
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload);
    }

    // ---- the first look at a chat: its recent history from Telegram, once ----

    private void backfill() {
        if (backfilling || !UserConfig.getInstance(account).isClientActivated()) {
            return;
        }
        final ArrayList<Long> todo = new ArrayList<>();
        final ArrayList<TLRPC.Dialog> dialogs = new ArrayList<>(MessagesController.getInstance(account).getAllDialogs());
        for (int i = 0; i < dialogs.size() && todo.size() < BACKFILL_DIALOGS; i++) {
            final TLRPC.Dialog d = dialogs.get(i);
            if (d == null || d.id == 0) {
                continue;
            }
            final Streak s = streaks.get(d.id);
            if ((s == null || !s.backfilled) && eligible(d.id)) {
                todo.add(d.id);
            }
        }
        if (todo.isEmpty()) {
            AndroidUtilities.runOnUIThread(this::backfill, 30 * 60_000); // new chats later
            return;
        }
        backfilling = true;
        backfillNext(todo, 0);
    }

    private void backfillNext(ArrayList<Long> todo, int index) {
        if (index >= todo.size()) {
            backfilling = false;
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload);
            AndroidUtilities.runOnUIThread(this::backfill, 30 * 60_000);
            return;
        }
        backfillDialog(todo.get(index), () -> AndroidUtilities.runOnUIThread(() -> backfillNext(todo, index + 1), 700));
    }

    private void backfillDialog(long dialogId, Runnable done) {
        final TLRPC.InputPeer peer = MessagesController.getInstance(account).getInputPeer(dialogId);
        if (peer == null) {
            obtain(dialogId).backfilled = true;
            done.run();
            return;
        }
        final HashMap<Integer, long[]> days = new HashMap<>();
        final int[] info = new int[]{0, 0}; // total messages, first message's day
        requestPage(peer, dialogId, 0, 0, days, info, () -> requestFirst(peer, info, () -> {
            applyBackfill(dialogId, days, info);
            done.run();
        }));
    }

    private void requestPage(TLRPC.InputPeer peer, long dialogId, int offsetId, int page, HashMap<Integer, long[]> days, int[] info, Runnable done) {
        final TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
        req.peer = peer;
        req.offset_id = offsetId;
        req.limit = 100;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (!(response instanceof TLRPC.messages_Messages)) {
                done.run();
                return;
            }
            final TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
            if (page == 0) {
                info[0] = res instanceof TLRPC.TL_messages_messages ? res.messages.size() : res.count;
            }
            int oldestId = 0;
            int oldestDay = Integer.MAX_VALUE;
            for (TLRPC.Message m : res.messages) {
                if (m == null) {
                    continue;
                }
                oldestId = oldestId == 0 ? m.id : Math.min(oldestId, m.id);
                if (m instanceof TLRPC.TL_messageService) {
                    continue;
                }
                final int d = day(m.date);
                oldestDay = Math.min(oldestDay, d);
                long[] p = days.get(d);
                if (p == null) {
                    days.put(d, p = new long[2]);
                }
                if (dialogId < 0) {
                    final long sender = MessageObject.getPeerId(m.from_id);
                    if (p[1] == 0) {
                        p[1] = sender;
                    } else if (p[1] != sender) {
                        p[0] |= TWO_SENDERS;
                    }
                } else {
                    p[0] |= m.out ? ME : THEM;
                }
            }
            final boolean more = res.messages.size() >= 100 && oldestId > 1 && page + 1 < BACKFILL_MAX_PAGES
                && oldestDay > today() - BACKFILL_MAX_DAYS && !runEndsAfter(days, dialogId, oldestDay);
            if (more) {
                requestPage(peer, dialogId, oldestId, page + 1, days, info, done);
            } else {
                done.run();
            }
        }));
    }

    // the run that reaches today already starts after the oldest day read, so older pages change nothing
    private static boolean runEndsAfter(HashMap<Integer, long[]> days, long dialogId, int oldestRead) {
        int quiet = 0;
        for (int d = today(); d > oldestRead; d--) {
            final long[] p = days.get(d);
            if (p != null && mutual(dialogId, p[0])) {
                quiet = 0;
            } else if (++quiet >= 3) {
                return true;
            }
        }
        return false;
    }

    private static boolean mutual(long dialogId, long flags) {
        return dialogId < 0 ? (flags & TWO_SENDERS) != 0 : (flags & (ME | THEM)) == (ME | THEM);
    }

    // the chat's first message: its date says how long they have known each other
    private void requestFirst(TLRPC.InputPeer peer, int[] info, Runnable done) {
        final TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
        req.peer = peer;
        req.offset_id = 1;
        req.add_offset = -1;
        req.limit = 1;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (response instanceof TLRPC.messages_Messages && !((TLRPC.messages_Messages) response).messages.isEmpty()) {
                info[1] = day(((TLRPC.messages_Messages) response).messages.get(0).date);
            }
            done.run();
        }));
    }

    private void applyBackfill(long dialogId, HashMap<Integer, long[]> days, int[] info) {
        final Streak s = obtain(dialogId);
        final ArrayList<Integer> sorted = new ArrayList<>(days.keySet());
        java.util.Collections.sort(sorted);
        final Streak fresh = new Streak();
        for (int d : sorted) {
            if (mutual(dialogId, days.get(d)[0])) {
                addMutual(fresh, d);
            }
        }
        s.count = fresh.count;
        s.start = fresh.start;
        s.last = fresh.last;
        s.lostCount = fresh.lostCount;
        s.lostLast = fresh.lostLast;
        s.record = Math.max(s.record, fresh.record);
        s.total = Math.max(s.total, fresh.total);
        if (info[1] > 0) {
            s.firstDay = info[1];
        } else if (!sorted.isEmpty() && (s.firstDay == 0 || sorted.get(0) < s.firstDay)) {
            s.firstDay = sorted.get(0);
        }
        s.messages = Math.max(s.messages, info[0]);
        // today's and the last days' partial flags, so the next message completes them correctly
        final int t = today();
        s.pending.clear();
        for (int d = t - 3; d <= t; d++) {
            final long[] p = days.get(d);
            if (p != null) {
                s.pending.put(d, new long[]{p[0] | (mutual(dialogId, p[0]) ? COUNTED : 0), p[1]});
            }
        }
        // a streak that was already long when NexGramm met it is not offered as a story again for old milestones
        for (int m : MILESTONES) {
            if (s.count >= m) {
                s.milestone = Math.max(s.milestone, m);
            }
        }
        s.backfilled = true;
        saveSoon();
    }

    // ---- storage: one small JSON file per account in the app's private files ----

    private File file() {
        return new File(ApplicationLoader.getFilesDirFixed(), "nexgramm_streaks_" + account + ".json");
    }

    private void load() {
        try {
            final File f = file();
            if (!f.exists()) {
                return;
            }
            final byte[] bytes = new byte[(int) f.length()];
            try (FileInputStream in = new FileInputStream(f)) {
                int read = 0;
                while (read < bytes.length) {
                    final int n = in.read(bytes, read, bytes.length - read);
                    if (n < 0) {
                        break;
                    }
                    read += n;
                }
            }
            final JSONArray arr = new JSONArray(new String(bytes, StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) {
                final JSONObject o = arr.getJSONObject(i);
                final Streak s = new Streak();
                s.dialogId = o.getLong("d");
                s.count = o.optInt("c");
                s.start = o.optInt("s");
                s.last = o.optInt("l");
                s.lostCount = o.optInt("lc");
                s.lostLast = o.optInt("ll");
                s.record = o.optInt("r");
                s.total = o.optInt("t");
                s.firstDay = o.optInt("f");
                s.frozenUntil = o.optInt("z");
                s.messages = o.optInt("m");
                s.milestone = o.optInt("ms");
                s.backfilled = o.optBoolean("b");
                final JSONArray p = o.optJSONArray("p");
                if (p != null) {
                    for (int j = 0; j + 2 < p.length(); j += 3) {
                        s.pending.put(p.getInt(j), new long[]{p.getLong(j + 1), p.getLong(j + 2)});
                    }
                }
                streaks.put(s.dialogId, s);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void saveSoon() {
        if (saveQueued) {
            return;
        }
        saveQueued = true;
        AndroidUtilities.runOnUIThread(() -> {
            saveQueued = false;
            final String json = toJson();
            final File f = file();
            DISK.execute(() -> {
                final File tmp = new File(f.getPath() + ".tmp");
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    out.write(json.getBytes(StandardCharsets.UTF_8));
                    out.getFD().sync();
                } catch (Exception e) {
                    FileLog.e(e);
                    return;
                }
                if (!tmp.renameTo(f)) {
                    tmp.delete();
                }
            });
        }, 2000);
    }

    private String toJson() {
        final JSONArray arr = new JSONArray();
        try {
            for (Streak s : streaks.values()) {
                final JSONObject o = new JSONObject();
                o.put("d", s.dialogId);
                o.put("c", s.count);
                o.put("s", s.start);
                o.put("l", s.last);
                o.put("lc", s.lostCount);
                o.put("ll", s.lostLast);
                o.put("r", s.record);
                o.put("t", s.total);
                o.put("f", s.firstDay);
                o.put("z", s.frozenUntil);
                o.put("m", s.messages);
                o.put("ms", s.milestone);
                o.put("b", s.backfilled);
                final JSONArray p = new JSONArray();
                for (java.util.Map.Entry<Integer, long[]> e : s.pending.entrySet()) {
                    p.put(e.getKey()).put(e.getValue()[0]).put(e.getValue()[1]);
                }
                o.put("p", p);
                arr.put(o);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return arr.toString();
    }
}
