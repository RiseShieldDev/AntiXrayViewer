package com.example.antixrayai.commands;

import com.example.antixrayai.AntiXrayAI;
import com.example.antixrayai.data.PlayerRecording;
import com.example.antixrayai.managers.RecordingManager;
import com.example.antixrayai.replay.ReplaySession;
import com.example.antixrayai.replay.SmoothReplaySession;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

public class AntiXrayCommand implements CommandExecutor, TabCompleter {
    
    private final AntiXrayAI plugin;
    private final RecordingManager recordingManager;
    private final Map<UUID, Object> replaySessions = new HashMap<>(); // Может быть ReplaySession или SmoothReplaySession
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("dd.MM HH:mm:ss");
    private static final int RECORDINGS_PER_PAGE = 8; // Количество записей на странице
    
    public AntiXrayCommand(AntiXrayAI plugin, RecordingManager recordingManager) {
        this.plugin = plugin;
        this.recordingManager = recordingManager;
    }
    
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cЭта команда доступна только игрокам!");
            return true;
        }
        
        Player player = (Player) sender;
        
        if (!player.hasPermission("antixrayai.admin")) {
            player.sendMessage("§cУ вас нет прав для использования этой команды!");
            return true;
        }
        
        if (args.length == 0) {
            sendHelp(player);
            return true;
        }
        
        switch (args[0].toLowerCase()) {
            case "list":
                int page = 1;
                if (args.length > 1) {
                    try {
                        page = Integer.parseInt(args[1]);
                    } catch (NumberFormatException e) {
                        player.sendMessage("§cНеверный номер страницы!");
                        return true;
                    }
                }
                handleList(player, page);
                break;
                
            case "view":
            case "play":
                if (args.length < 2) {
                    player.sendMessage("§cИспользование: /" + label + " view <id> [smooth|normal]");
                    return true;
                }
                String mode = "normal";
                if (args.length > 2) {
                    mode = args[2].toLowerCase();
                }
                handleView(player, args[1], mode);
                break;
                
            case "delete":
            case "remove":
                if (args.length < 2) {
                    player.sendMessage("§cИспользование: /" + label + " delete <id>");
                    return true;
                }
                handleDelete(player, args[1]);
                break;
                
            case "stop":
                handleStop(player);
                break;
                
            case "active":
                handleActive(player);
                break;
                
            case "reload":
            case "refresh":
                handleReload(player);
                break;
                
            case "help":
            default:
                sendHelp(player);
                break;
        }
        
        return true;
    }
    
    private void handleList(Player player, int page) {
        List<PlayerRecording> allRecordings = recordingManager.getCompletedRecordings();
        
        if (allRecordings.isEmpty()) {
            player.sendMessage("§7Нет сохраненных записей.");
            return;
        }
        
        // Сортируем записи от новых к старым (по ID в обратном порядке)
        List<PlayerRecording> sortedRecordings = new ArrayList<>(allRecordings);
        sortedRecordings.sort((r1, r2) -> Integer.compare(r2.getId(), r1.getId()));
        
        // Вычисляем пагинацию
        int totalRecordings = sortedRecordings.size();
        int totalPages = (int) Math.ceil((double) totalRecordings / RECORDINGS_PER_PAGE);
        
        // Проверяем корректность страницы
        if (page < 1) page = 1;
        if (page > totalPages) page = totalPages;
        
        // Вычисляем индексы для текущей страницы
        int startIndex = (page - 1) * RECORDINGS_PER_PAGE;
        int endIndex = Math.min(startIndex + RECORDINGS_PER_PAGE, totalRecordings);
        
        // Заголовок с номером страницы
        player.sendMessage(String.format("§6═══════ §eЗаписи AntiXrayAI §7[§f%d§7/§f%d§7] §6═══════", page, totalPages));
        
        // Отображаем записи текущей страницы
        for (int i = startIndex; i < endIndex; i++) {
            PlayerRecording recording = sortedRecordings.get(i);
            Date date = new Date(recording.getStartTime());
            String status = recording.getFrameCount() > 0 ? "§a✓" : "§c✗";
            
            // Создаем кликабельный ID для быстрого просмотра
            TextComponent idComponent = new TextComponent(String.format("%s §7#§b%d", status, recording.getId()));
            idComponent.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/axai view " + recording.getId()));
            idComponent.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ComponentBuilder("§aНажмите для просмотра записи #" + recording.getId()).create()));
            
            // Остальная информация о записи
            TextComponent infoComponent = new TextComponent(String.format(
                " §7| §f%s §7| %s §7| Кадров: §e%d §7| §f%ds",
                recording.getPlayerName(),
                dateFormat.format(date),
                recording.getFrameCount(),
                recording.getDurationSeconds()
            ));
            
            // Отправляем компоненты
            idComponent.addExtra(infoComponent);
            player.spigot().sendMessage(idComponent);
            
            // Причина на отдельной строке
            player.sendMessage("  §7Причина: §e" + recording.getReason());
        }
        
        player.sendMessage("§6════════════════════════════════════════");
        
        // Создаем навигационную панель
        sendNavigationBar(player, page, totalPages);
        
        // Подсказка
        player.sendMessage("§7Используйте §f/axai view <id> [smooth|normal] §7или кликните на ID");
    }
    
    private void sendNavigationBar(Player player, int currentPage, int totalPages) {
        TextComponent navigation = new TextComponent("");
        
        // Кнопка "Предыдущая страница"
        if (currentPage > 1) {
            TextComponent prevButton = new TextComponent("§a◀ Предыдущая ");
            prevButton.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/axai list " + (currentPage - 1)));
            prevButton.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ComponentBuilder("§aПерейти на страницу " + (currentPage - 1)).create()));
            navigation.addExtra(prevButton);
        } else {
            navigation.addExtra("§8◀ Предыдущая ");
        }
        
        // Информация о текущей странице
        navigation.addExtra(String.format("§7[§f%d§7/§f%d§7]", currentPage, totalPages));
        
        // Кнопка "Следующая страница"
        if (currentPage < totalPages) {
            TextComponent nextButton = new TextComponent(" §aСледующая ▶");
            nextButton.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/axai list " + (currentPage + 1)));
            nextButton.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ComponentBuilder("§aПерейти на страницу " + (currentPage + 1)).create()));
            navigation.addExtra(nextButton);
        } else {
            navigation.addExtra(" §8Следующая ▶");
        }
        
        player.spigot().sendMessage(navigation);
        
        // Добавляем быстрый переход к страницам
        if (totalPages > 5) {
            TextComponent quickNav = new TextComponent("§7Быстрый переход: ");
            
            // Первая страница
            if (currentPage != 1) {
                TextComponent firstPage = new TextComponent("§e[1] ");
                firstPage.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/axai list 1"));
                firstPage.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    new ComponentBuilder("§aПерейти к первой странице").create()));
                quickNav.addExtra(firstPage);
            }
            
            // Показываем несколько страниц вокруг текущей
            int start = Math.max(2, currentPage - 2);
            int end = Math.min(totalPages - 1, currentPage + 2);
            
            for (int i = start; i <= end; i++) {
                if (i == currentPage) {
                    quickNav.addExtra("§f[" + i + "] ");
                } else {
                    TextComponent pageLink = new TextComponent("§e[" + i + "] ");
                    pageLink.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/axai list " + i));
                    pageLink.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        new ComponentBuilder("§aПерейти на страницу " + i).create()));
                    quickNav.addExtra(pageLink);
                }
            }
            
            // Последняя страница
            if (currentPage != totalPages) {
                TextComponent lastPage = new TextComponent("§e[" + totalPages + "]");
                lastPage.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/axai list " + totalPages));
                lastPage.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    new ComponentBuilder("§aПерейти к последней странице").create()));
                quickNav.addExtra(lastPage);
            }
            
            player.spigot().sendMessage(quickNav);
        }
    }
    
    private void handleView(Player player, String idStr, String mode) {
        try {
            int id = Integer.parseInt(idStr);
            PlayerRecording recording = recordingManager.getRecording(id);
            
            if (recording == null) {
                player.sendMessage("§cЗапись с ID #" + id + " не найдена!");
                return;
            }
            
            if (recording.getFrameCount() == 0) {
                player.sendMessage("§cЗапись пуста!");
                return;
            }
            
            // Останавливаем предыдущую сессию воспроизведения, если есть
            stopReplay(player);
            
            // Создаем новую сессию воспроизведения в зависимости от режима
            if (mode.equals("smooth") || mode.equals("s")) {
                SmoothReplaySession session = new SmoothReplaySession(plugin, player, recording);
                replaySessions.put(player.getUniqueId(), session);
                
                player.sendMessage("§a▶ Начинаю §bплавное§a воспроизведение записи #" + id);
                player.sendMessage("§7Режим: §bПлавная интерполяция движения");
                player.sendMessage("§7Игрок: §f" + recording.getPlayerName());
                player.sendMessage("§7Причина: §e" + recording.getReason());
                player.sendMessage("§7Длительность: §f" + recording.getDurationSeconds() + " секунд");
                player.sendMessage("§7Используйте §f/axai stop §7для остановки");
                
                session.start();
            } else {
                ReplaySession session = new ReplaySession(plugin, player, recording);
                replaySessions.put(player.getUniqueId(), session);
                
                player.sendMessage("§a▶ Начинаю воспроизведение записи #" + id);
                player.sendMessage("§7Режим: §fОбычный");
                player.sendMessage("§7Игрок: §f" + recording.getPlayerName());
                player.sendMessage("§7Причина: §e" + recording.getReason());
                player.sendMessage("§7Длительность: §f" + recording.getDurationSeconds() + " секунд");
                player.sendMessage("§7Используйте §f/axai stop §7для остановки");
                
                session.start();
            }
            
        } catch (NumberFormatException e) {
            player.sendMessage("§cНеверный ID записи!");
        }
    }
    
    private void handleDelete(Player player, String idStr) {
        try {
            int id = Integer.parseInt(idStr);
            
            if (recordingManager.deleteRecording(id)) {
                player.sendMessage("§aЗапись #" + id + " успешно удалена!");
            } else {
                player.sendMessage("§cЗапись с ID #" + id + " не найдена!");
            }
            
        } catch (NumberFormatException e) {
            player.sendMessage("§cНеверный ID записи!");
        }
    }
    
    private void handleStop(Player player) {
        if (stopReplay(player)) {
            player.sendMessage("§aВоспроизведение остановлено.");
        } else {
            player.sendMessage("§7Вы не просматриваете запись.");
        }
    }
    
    private void handleActive(Player player) {
        Map<UUID, PlayerRecording> active = recordingManager.getActiveRecordings();
        
        if (active.isEmpty()) {
            player.sendMessage("§7Нет активных записей.");
            return;
        }
        
        player.sendMessage("§6═══════ §eАктивные записи §6═══════");
        
        for (PlayerRecording recording : active.values()) {
            int seconds = recording.getDurationSeconds();
            player.sendMessage(String.format(
                "§c● §f%s §7- записывается %ds (кадров: %d)",
                recording.getPlayerName(),
                seconds,
                recording.getFrameCount()
            ));
            player.sendMessage("  §7Причина: §e" + recording.getReason());
        }
        
        player.sendMessage("§6═════════════════════════════════");
    }
    
    private void handleReload(Player player) {
        player.sendMessage("§eПерезагружаю записи из файлов...");
        
        // Синхронизируем с файловой системой
        recordingManager.syncRecordingsWithFileSystem();
        
        // Или полностью перезагружаем из файлов
        // recordingManager.reloadRecordings();
        
        List<PlayerRecording> recordings = recordingManager.getCompletedRecordings();
        player.sendMessage("§aЗаписи синхронизированы! Загружено: §f" + recordings.size() + " §aзаписей.");
    }
    
    private boolean stopReplay(Player player) {
        Object session = replaySessions.remove(player.getUniqueId());
        if (session != null) {
            if (session instanceof ReplaySession) {
                ((ReplaySession) session).stop();
            } else if (session instanceof SmoothReplaySession) {
                ((SmoothReplaySession) session).stop();
            }
            return true;
        }
        return false;
    }
    
    private void sendHelp(Player player) {
        player.sendMessage("§6═══════════ §eAntiXrayAI §6═══════════");
        player.sendMessage("§f/axai list [страница] §7- список всех записей");
        player.sendMessage("§f/axai view <id> [smooth|normal] §7- просмотреть запись");
        player.sendMessage("  §7smooth §b- плавное воспроизведение");
        player.sendMessage("  §7normal §f- обычное воспроизведение (по умолчанию)");
        player.sendMessage("§f/axai delete <id> §7- удалить запись");
        player.sendMessage("§f/axai stop §7- остановить просмотр");
        player.sendMessage("§f/axai active §7- активные записи");
        player.sendMessage("§f/axai reload §7- синхронизировать записи");
        player.sendMessage("§f/axai help §7- показать эту справку");
        player.sendMessage("§6═════════════════════════════════════");
    }
    
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("antixrayai.admin")) {
            return Collections.emptyList();
        }
        
        if (args.length == 1) {
            return Arrays.asList("list", "view", "delete", "stop", "active", "reload", "help")
                    .stream()
                    .filter(s -> s.toLowerCase().startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        
        if (args.length == 2) {
            if (args[0].equalsIgnoreCase("list")) {
                // Предлагаем номера страниц
                List<PlayerRecording> recordings = recordingManager.getCompletedRecordings();
                int totalPages = (int) Math.ceil((double) recordings.size() / RECORDINGS_PER_PAGE);
                List<String> pages = new ArrayList<>();
                for (int i = 1; i <= totalPages; i++) {
                    pages.add(String.valueOf(i));
                }
                return pages.stream()
                        .filter(s -> s.startsWith(args[1]))
                        .collect(Collectors.toList());
            } else if (args[0].equalsIgnoreCase("view") || args[0].equalsIgnoreCase("play")) {
                // Предлагаем ID записей
                return recordingManager.getCompletedRecordings().stream()
                        .map(r -> String.valueOf(r.getId()))
                        .filter(s -> s.startsWith(args[1]))
                        .collect(Collectors.toList());
            } else if (args[0].equalsIgnoreCase("delete")) {
                // Предлагаем ID записей
                return recordingManager.getCompletedRecordings().stream()
                        .map(r -> String.valueOf(r.getId()))
                        .filter(s -> s.startsWith(args[1]))
                        .collect(Collectors.toList());
            }
        }
        
        if (args.length == 3) {
            if (args[0].equalsIgnoreCase("view") || args[0].equalsIgnoreCase("play")) {
                // Предлагаем режимы воспроизведения
                return Arrays.asList("smooth", "normal")
                        .stream()
                        .filter(s -> s.startsWith(args[2].toLowerCase()))
                        .collect(Collectors.toList());
            }
        }
        
        return Collections.emptyList();
    }
    
    public void stopAllReplays() {
        for (Object session : replaySessions.values()) {
            if (session instanceof ReplaySession) {
                ((ReplaySession) session).stop();
            } else if (session instanceof SmoothReplaySession) {
                ((SmoothReplaySession) session).stop();
            }
        }
        replaySessions.clear();
    }
}