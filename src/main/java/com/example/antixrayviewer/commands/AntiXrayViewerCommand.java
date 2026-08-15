package com.example.antixrayviewer.commands;

import com.example.antixrayviewer.AntiXrayViewer;
import com.example.antixrayviewer.data.PlayerRecording;
import com.example.antixrayviewer.managers.RecordingManager;
import com.example.antixrayviewer.replay.CameraMode;
import com.example.antixrayviewer.replay.ReplayManager;
import com.example.antixrayviewer.replay.ReplaySession;
import com.example.antixrayviewer.replay.ReplayTimeline;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Обработчик команд плагина.
 *
 * Сессии просмотра больше не хранятся здесь — за них отвечает ReplayManager,
 * который гарантированно чистит их при выходе игрока и выключении сервера.
 */
public class AntiXrayViewerCommand implements CommandExecutor, TabCompleter {

    private static final int RECORDINGS_PER_PAGE = 8;

    private final AntiXrayViewer plugin;
    private final RecordingManager recordingManager;
    private final ReplayManager replayManager;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("dd.MM HH:mm:ss");

    public AntiXrayViewerCommand(AntiXrayViewer plugin, RecordingManager recordingManager, ReplayManager replayManager) {
        this.plugin = plugin;
        this.recordingManager = recordingManager;
        this.replayManager = replayManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Эта команда доступна только игрокам.");
            return true;
        }

        if (args.length == 0) {
            sendHelp(player);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list":
            case "список":
                handleList(player, args.length > 1 ? parseInt(args[1], 1) : 1);
                return true;
            case "view":
            case "play":
            case "смотреть":
                if (args.length < 2) {
                    error(player, "Использование: /axv view <id>");
                    return true;
                }
                handleView(player, args[1]);
                return true;
            case "stop":
                if (!replayManager.stop(player)) {
                    error(player, "У вас нет активного просмотра.");
                }
                return true;
            case "pause":
            case "resume": {
                ReplaySession session = requireSession(player);
                if (session != null) {
                    session.togglePause();
                    feedback(player, session.isPaused() ? "⏸ Пауза." : "▶ Воспроизведение.");
                }
                return true;
            }
            case "speed": {
                ReplaySession session = requireSession(player);
                if (session == null) {
                    return true;
                }
                if (args.length < 2) {
                    error(player, "Использование: /axv speed <0.1-8, можно отрицательную для реверса>");
                    return true;
                }
                double value = parseDouble(args[1], Double.NaN);
                if (Double.isNaN(value) || value == 0) {
                    error(player, "Неверное значение скорости.");
                    return true;
                }
                session.setSpeed(value);
                feedback(player, "Скорость: x" + session.getSpeed());
                return true;
            }
            case "seek": {
                ReplaySession session = requireSession(player);
                if (session == null) {
                    return true;
                }
                if (args.length < 2) {
                    error(player, "Использование: /axv seek <время>, например 45 или 1:30");
                    return true;
                }
                long time = parseTime(args[1], -1L);
                if (time < 0) {
                    error(player, "Не понял время. Примеры: 45, 1:30, 2m10s");
                    return true;
                }
                session.seek(time);
                feedback(player, "Переход к " + ReplaySession.formatTime(session.getClock()));
                return true;
            }
            case "jump":
            case "skip": {
                ReplaySession session = requireSession(player);
                if (session == null) {
                    return true;
                }
                if (args.length < 2) {
                    error(player, "Использование: /axv jump <±секунды>");
                    return true;
                }
                double seconds = parseDouble(args[1], Double.NaN);
                if (Double.isNaN(seconds)) {
                    error(player, "Нужно число секунд, например -10 или 5");
                    return true;
                }
                session.jump((long) (seconds * 1000));
                feedback(player, "Текущее время: " + ReplaySession.formatTime(session.getClock()));
                return true;
            }
            case "range":
            case "отрезок": {
                ReplaySession session = requireSession(player);
                if (session == null) {
                    return true;
                }
                if (args.length == 2 && args[1].equalsIgnoreCase("clear")) {
                    session.clearRange();
                    feedback(player, "Отрезок сброшен, воспроизводится вся запись.");
                    return true;
                }
                if (args.length == 2 && (args[1].equalsIgnoreCase("a") || args[1].equalsIgnoreCase("start"))) {
                    session.setRangeStartHere();
                    feedback(player, "Начало отрезка: " + ReplaySession.formatTime(session.getRangeStart()));
                    return true;
                }
                if (args.length == 2 && (args[1].equalsIgnoreCase("b") || args[1].equalsIgnoreCase("end"))) {
                    session.setRangeEndHere();
                    feedback(player, "Конец отрезка: " + ReplaySession.formatTime(session.getRangeEnd()));
                    return true;
                }
                if (args.length < 3) {
                    error(player, "Использование: /axv range <от> <до> или /axv range clear");
                    return true;
                }
                long from = parseTime(args[1], -1L);
                long to = parseTime(args[2], -1L);
                if (from < 0 || to < 0) {
                    error(player, "Неверные границы отрезка.");
                    return true;
                }
                session.setRange(from, to);
                feedback(player, "Отрезок: " + ReplaySession.formatTime(session.getRangeStart())
                        + " — " + ReplaySession.formatTime(session.getRangeEnd()));
                return true;
            }
            case "loop": {
                ReplaySession session = requireSession(player);
                if (session != null) {
                    session.setLoop(!session.isLoop());
                    feedback(player, session.isLoop() ? "↻ Повтор включён." : "↻ Повтор выключен.");
                }
                return true;
            }
            case "marker": {
                ReplaySession session = requireSession(player);
                if (session == null) {
                    return true;
                }
                boolean forward = args.length < 2 || !args[1].equalsIgnoreCase("prev");
                boolean moved = forward ? session.nextMarker() : session.previousMarker();
                if (!moved) {
                    error(player, forward ? "Следующих событий нет." : "Предыдущих событий нет.");
                } else {
                    feedback(player, "Переход к событию: " + ReplaySession.formatTime(session.getClock()));
                }
                return true;
            }
            case "camera":
            case "камера": {
                ReplaySession session = requireSession(player);
                if (session == null) {
                    return true;
                }
                CameraMode mode;
                if (args.length < 2 || args[1].equalsIgnoreCase("cycle")) {
                    mode = session.getCameraMode().next();
                } else {
                    mode = CameraMode.parse(args[1], null);
                    if (mode == null) {
                        error(player, "Режимы: first, third, free");
                        return true;
                    }
                }
                session.setCameraMode(mode);
                feedback(player, "Камера: " + mode.getDisplayName());
                return true;
            }
            case "timeline":
            case "panel":
            case "панель": {
                ReplaySession session = requireSession(player);
                if (session != null) {
                    session.sendPanel();
                }
                return true;
            }
            case "follow":
            case "кигроку": {
                ReplaySession session = requireSession(player);
                if (session != null) {
                    session.followPlayer();
                    feedback(player, "Камера перенесена к игроку.");
                }
                return true;
            }
            case "info": {
                ReplaySession session = requireSession(player);
                if (session != null) {
                    sendSessionInfo(player, session);
                }
                return true;
            }
            case "delete":
            case "remove":
                if (!player.hasPermission("antixrayviewer.admin")) {
                    error(player, "Нет прав.");
                    return true;
                }
                if (args.length < 2) {
                    error(player, "Использование: /axv delete <id>");
                    return true;
                }
                handleDelete(player, args[1]);
                return true;
            case "active":
                handleActive(player);
                return true;
            case "reload":
                if (!player.hasPermission("antixrayviewer.reload")) {
                    error(player, "Нет прав.");
                    return true;
                }
                plugin.reloadConfig();
                recordingManager.reloadRecordings();
                info(player, "Конфигурация и записи перезагружены.");
                return true;
            case "help":
            default:
                sendHelp(player);
                return true;
        }
    }

    // ===================== Обработчики =====================

    private ReplaySession requireSession(Player player) {
        ReplaySession session = replayManager.get(player);
        if (session == null) {
            error(player, "Сначала откройте запись: /axv view <id>");
        }
        return session;
    }

    private void handleList(Player player, int page) {
        List<PlayerRecording> recordings = recordingManager.getCompletedRecordings();
        if (recordings.isEmpty()) {
            info(player, "Записей пока нет.");
            return;
        }

        int totalPages = (recordings.size() + RECORDINGS_PER_PAGE - 1) / RECORDINGS_PER_PAGE;
        int current = Math.max(1, Math.min(totalPages, page));
        int start = (current - 1) * RECORDINGS_PER_PAGE;
        int end = Math.min(recordings.size(), start + RECORDINGS_PER_PAGE);

        player.sendMessage(Component.text("═══ Записи (стр. " + current + "/" + totalPages + ") ═══", NamedTextColor.GOLD)
                .decorate(TextDecoration.BOLD));

        for (int i = start; i < end; i++) {
            PlayerRecording recording = recordings.get(i);
            Component line = Component.text("#" + recording.getId() + " ", NamedTextColor.AQUA)
                    .append(Component.text(recording.getPlayerName(), NamedTextColor.WHITE))
                    .append(Component.text("  " + recording.getDurationSeconds() + "с", NamedTextColor.GRAY))
                    .append(Component.text("  " + dateFormat.format(new Date(recording.getStartTime())), NamedTextColor.DARK_GRAY))
                    .append(Component.text("  [Смотреть]", NamedTextColor.GREEN)
                            .clickEvent(ClickEvent.runCommand("/axv view " + recording.getId()))
                            .hoverEvent(HoverEvent.showText(Component.text("Причина: " + recording.getReason()))));
            player.sendMessage(line);
        }

        Component nav = Component.empty();
        if (current > 1) {
            nav = nav.append(Component.text("[« Назад] ", NamedTextColor.YELLOW)
                    .clickEvent(ClickEvent.runCommand("/axv list " + (current - 1))));
        }
        if (current < totalPages) {
            nav = nav.append(Component.text("[Вперёд »]", NamedTextColor.YELLOW)
                    .clickEvent(ClickEvent.runCommand("/axv list " + (current + 1))));
        }
        player.sendMessage(nav);
    }

    private void handleView(Player player, String idRaw) {
        int id = parseInt(idRaw, -1);
        if (id < 0) {
            error(player, "ID должен быть числом.");
            return;
        }
        PlayerRecording recording = recordingManager.getRecording(id);
        if (recording == null) {
            error(player, "Запись #" + id + " не найдена.");
            return;
        }
        if (recording.getFrameCount() == 0) {
            error(player, "В записи #" + id + " нет кадров.");
            return;
        }

        ReplaySession session = replayManager.start(player, recording);
        sendSessionInfo(player, session);
    }

    private void handleDelete(Player player, String idRaw) {
        int id = parseInt(idRaw, -1);
        if (id < 0) {
            error(player, "ID должен быть числом.");
            return;
        }
        if (recordingManager.deleteRecording(id)) {
            info(player, "Запись #" + id + " удалена.");
        } else {
            error(player, "Запись #" + id + " не найдена.");
        }
    }

    private void handleActive(Player player) {
        Map<UUID, PlayerRecording> active = recordingManager.getActiveRecordings();
        player.sendMessage(Component.text("Активные записи: " + active.size()
                + " | Активные просмотры: " + replayManager.getActiveCount(), NamedTextColor.GOLD));
        for (PlayerRecording recording : active.values()) {
            player.sendMessage(Component.text(" • " + recording.getPlayerName() + " — " + recording.getReason(),
                    NamedTextColor.GRAY));
        }
    }

    private void sendSessionInfo(Player player, ReplaySession session) {
        ReplayTimeline timeline = session.getTimeline();
        player.sendMessage(Component.text("Запись #" + session.getRecording().getId()
                + " | Длительность: " + ReplaySession.formatTime(timeline.getDuration())
                + " | Кадров: " + timeline.getFrameCount()
                + " | Изменений блоков: " + timeline.getDeltas().size()
                + " | Руды: " + timeline.getOreBreakCount(), NamedTextColor.DARK_AQUA));
    }

    private void sendHelp(Player player) {
        player.sendMessage(Component.text("═══ AntiXrayViewer ═══", NamedTextColor.GOLD).decorate(TextDecoration.BOLD));
        help(player, "/axv list [стр]", "список записей");
        help(player, "/axv view <id>", "открыть запись");
        help(player, "/axv pause", "пауза / продолжить");
        help(player, "/axv seek <сек|м:сс>", "перейти на время");
        help(player, "/axv jump <±сек>", "пролистать вперёд/назад");
        help(player, "/axv range <от> <до>", "воспроизводить только отрезок");
        help(player, "/axv loop", "повтор отрезка");
        help(player, "/axv speed <x>", "скорость (отрицательная = назад)");
        help(player, "/axv marker next|prev", "прыжок к добыче руды");
        help(player, "/axv camera <first|third|free>", "режим камеры");
        help(player, "/axv follow", "перенести свободную камеру к игроку");
        help(player, "/axv range a|b|clear", "отметить отрезок по текущему времени");
        help(player, "/axv panel", "панель управления с кнопками");
        help(player, "/axv stop", "завершить просмотр");
        help(player, "/axv active", "активные записи и просмотры");
        if (player.hasPermission("antixrayviewer.admin")) {
            help(player, "/axv delete <id>", "удалить запись");
        }
        if (player.hasPermission("antixrayviewer.reload")) {
            help(player, "/axv reload", "перезагрузить конфиг");
        }
    }

    private void help(Player player, String command, String description) {
        player.sendMessage(Component.text(command, NamedTextColor.YELLOW)
                .append(Component.text(" — " + description, NamedTextColor.GRAY)));
    }

    /**
     * Ответ на нажатие кнопки панели — в action bar над хотбаром, а не в чат.
     * Так при активной перемотке чат не забивается и репорты игроков остаются видны.
     */
    private void feedback(Player player, String message) {
        player.sendActionBar(Component.text(message, NamedTextColor.AQUA));
    }

    private void info(Player player, String message) {
        player.sendMessage(Component.text("[AXV] ", NamedTextColor.AQUA)
                .append(Component.text(message, NamedTextColor.WHITE)));
    }

    private void error(Player player, String message) {
        player.sendMessage(Component.text("[AXV] ", NamedTextColor.RED)
                .append(Component.text(message, NamedTextColor.GRAY)));
    }

    // ===================== Парсеры =====================

    private static int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double parseDouble(String raw, double fallback) {
        try {
            return Double.parseDouble(raw.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Понимает форматы: 45, 45s, 1:30, 2m10s.
     *
     * @return время в миллисекундах или fallback
     */
    private static long parseTime(String raw, long fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);

        if (value.contains(":")) {
            String[] parts = value.split(":");
            if (parts.length != 2) {
                return fallback;
            }
            int minutes = parseInt(parts[0], -1);
            int seconds = parseInt(parts[1], -1);
            if (minutes < 0 || seconds < 0) {
                return fallback;
            }
            return (minutes * 60L + seconds) * 1000L;
        }

        if (value.contains("m") || value.contains("s")) {
            long total = 0L;
            StringBuilder number = new StringBuilder();
            for (char c : value.toCharArray()) {
                if (Character.isDigit(c) || c == '.') {
                    number.append(c);
                } else if (c == 'm') {
                    total += (long) (parseDouble(number.toString(), 0) * 60000);
                    number.setLength(0);
                } else if (c == 's') {
                    total += (long) (parseDouble(number.toString(), 0) * 1000);
                    number.setLength(0);
                }
            }
            if (number.length() > 0) {
                total += (long) (parseDouble(number.toString(), 0) * 1000);
            }
            return total;
        }

        double seconds = parseDouble(value, Double.NaN);
        if (Double.isNaN(seconds) || seconds < 0) {
            return fallback;
        }
        return (long) (seconds * 1000);
    }

    // ===================== Автодополнение =====================

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<>();
        if (args.length == 1) {
            for (String candidate : Arrays.asList("list", "view", "stop", "pause", "speed", "seek", "jump", "range",
                    "loop", "marker", "camera", "follow", "panel", "timeline", "info", "active", "delete", "reload",
                    "help")) {
                if (candidate.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    result.add(candidate);
                }
            }
            return result;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            switch (sub) {
                case "view":
                case "delete":
                case "remove":
                    for (PlayerRecording recording : recordingManager.getCompletedRecordings()) {
                        result.add(String.valueOf(recording.getId()));
                    }
                    return result;
                case "speed":
                    return Arrays.asList("0.25", "0.5", "1", "2", "4", "8", "-1", "-2");
                case "jump":
                    return Arrays.asList("-30", "-10", "-3", "3", "10", "30");
                case "marker":
                    return Arrays.asList("next", "prev");
                case "camera":
                    return Arrays.asList("first", "third", "free", "cycle");
                case "range":
                    return Arrays.asList("a", "b", "clear", "0", "0:30", "1:00");
                case "seek":
                    return Arrays.asList("0", "0:30", "1:00", "2:00");
                default:
                    return result;
            }
        }

        if (args.length == 3 && sub.equals("range")) {
            return Arrays.asList("0:30", "1:00", "2:00", "3:00");
        }
        return result;
    }
}
