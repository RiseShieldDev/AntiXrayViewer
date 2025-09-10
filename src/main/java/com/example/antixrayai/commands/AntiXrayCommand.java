package com.example.antixrayai.commands;

import com.example.antixrayai.AntiXrayAI;
import com.example.antixrayai.data.PlayerRecording;
import com.example.antixrayai.managers.RecordingManager;
import com.example.antixrayai.replay.ReplaySession;
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
    private final Map<UUID, ReplaySession> replaySessions = new HashMap<>();
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("dd.MM HH:mm:ss");
    
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
                handleList(player);
                break;
                
            case "view":
            case "play":
                if (args.length < 2) {
                    player.sendMessage("§cИспользование: /" + label + " view <id>");
                    return true;
                }
                handleView(player, args[1]);
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
                
            case "help":
            default:
                sendHelp(player);
                break;
        }
        
        return true;
    }
    
    private void handleList(Player player) {
        List<PlayerRecording> recordings = recordingManager.getCompletedRecordings();
        
        if (recordings.isEmpty()) {
            player.sendMessage("§7Нет сохраненных записей.");
            return;
        }
        
        player.sendMessage("§6═══════════ §eЗаписи AntiXrayAI §6═══════════");
        
        for (PlayerRecording recording : recordings) {
            Date date = new Date(recording.getStartTime());
            String status = recording.getFrameCount() > 0 ? "§a✓" : "§c✗";
            
            player.sendMessage(String.format(
                "%s §7#§b%d §7| §f%s §7| %s §7| Кадров: §e%d §7| §f%ds",
                status,
                recording.getId(),
                recording.getPlayerName(),
                dateFormat.format(date),
                recording.getFrameCount(),
                recording.getDurationSeconds()
            ));
            player.sendMessage("  §7Причина: §e" + recording.getReason());
        }
        
        player.sendMessage("§6════════════════════════════════════════");
        player.sendMessage("§7Используйте §f/axai view <id> §7для просмотра");
    }
    
    private void handleView(Player player, String idStr) {
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
            
            // Создаем новую сессию воспроизведения
            ReplaySession session = new ReplaySession(plugin, player, recording);
            replaySessions.put(player.getUniqueId(), session);
            
            player.sendMessage("§a▶ Начинаю воспроизведение записи #" + id);
            player.sendMessage("§7Игрок: §f" + recording.getPlayerName());
            player.sendMessage("§7Причина: §e" + recording.getReason());
            player.sendMessage("§7Длительность: §f" + recording.getDurationSeconds() + " секунд");
            player.sendMessage("§7Используйте §f/axai stop §7для остановки");
            
            session.start();
            
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
    
    private boolean stopReplay(Player player) {
        ReplaySession session = replaySessions.remove(player.getUniqueId());
        if (session != null) {
            session.stop();
            return true;
        }
        return false;
    }
    
    private void sendHelp(Player player) {
        player.sendMessage("§6═══════════ §eAntiXrayAI §6═══════════");
        player.sendMessage("§f/axai list §7- список всех записей");
        player.sendMessage("§f/axai view <id> §7- просмотреть запись");
        player.sendMessage("§f/axai delete <id> §7- удалить запись");
        player.sendMessage("§f/axai stop §7- остановить просмотр");
        player.sendMessage("§f/axai active §7- активные записи");
        player.sendMessage("§f/axai help §7- показать эту справку");
        player.sendMessage("§6═════════════════════════════════════");
    }
    
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("antixrayai.admin")) {
            return Collections.emptyList();
        }
        
        if (args.length == 1) {
            return Arrays.asList("list", "view", "delete", "stop", "active", "help")
                    .stream()
                    .filter(s -> s.toLowerCase().startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        
        if (args.length == 2 && (args[0].equalsIgnoreCase("view") || 
                                  args[0].equalsIgnoreCase("delete"))) {
            // Предлагаем ID записей
            return recordingManager.getCompletedRecordings().stream()
                    .map(r -> String.valueOf(r.getId()))
                    .filter(s -> s.startsWith(args[1]))
                    .collect(Collectors.toList());
        }
        
        return Collections.emptyList();
    }
    
    public void stopAllReplays() {
        for (ReplaySession session : replaySessions.values()) {
            session.stop();
        }
        replaySessions.clear();
    }
}