package me.example.rulesquiz;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class RulesQuizPlugin extends JavaPlugin implements Listener, TabExecutor {

    // ====== Состояния ======
    private final Set<UUID> frozen = ConcurrentHashMap.newKeySet();        // заморозка НАША (после авторизации)
    private final Map<UUID, Integer> quizIndex = new ConcurrentHashMap<>(); // текущий вопрос
    private final Set<UUID> started = ConcurrentHashMap.newKeySet();        // чтобы не стартовать повторно

    // ожидание авторизации rLogin
    private final Set<UUID> waitingAuth = ConcurrentHashMap.newKeySet();

    // ====== Вопросы ======
    private List<Question> questions;

    // ====== Персистентность ======
    private File dataFile;
    private FileConfiguration data;

    // ====== GUI ======
    private static final int GUI_SIZE = 27;
    private static final int SLOT_NO = 11;
    private static final int SLOT_INFO = 13;
    private static final int SLOT_YES = 15;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadQuestions();

        dataFile = new File(getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            try {
                getDataFolder().mkdirs();
                dataFile.createNewFile();
            } catch (IOException e) {
                throw new RuntimeException("Cannot create data.yml", e);
            }
        }
        data = YamlConfiguration.loadConfiguration(dataFile);

        Bukkit.getPluginManager().registerEvents(this, this);

        Objects.requireNonNull(getCommand("rulesquiz")).setExecutor(this);
        Objects.requireNonNull(getCommand("rulesquiz")).setTabCompleter(this);
    }

    // ====== CONFIG ======
    private void loadQuestions() {
        questions = new ArrayList<>();
        for (var map : getConfig().getMapList("questions")) {
            String text = Objects.toString(map.get("text"), "").trim();
            String correct = Objects.toString(map.get("correct"), "").trim().toLowerCase(Locale.ROOT);
            String explain = Objects.toString(map.get("explain"), "").trim();

            // correct только "да"/"нет"
            if (!text.isEmpty() && (correct.equals("да") || correct.equals("нет"))) {
                questions.add(new Question(text, correct, explain));
            }
        }
        if (questions.isEmpty()) {
            questions.add(new Question("Можно ли ломать чужие сооружения?", "нет", "Гриферство запрещено."));
        }
    }

    private String cmsg(String path) {
        return ChatColor.translateAlternateColorCodes('&', getConfig().getString(path, ""));
    }

    // ====== DATA ======
    private boolean isCompleted(UUID uuid) {
        return data.getBoolean("players." + uuid + ".completed", false);
    }

    private void setCompleted(Player p) {
        String base = "players." + p.getUniqueId();
        data.set(base + ".name", p.getName());
        data.set(base + ".completed", true);
        saveData();
    }

    private void saveData() {
        try {
            data.save(dataFile);
        } catch (IOException e) {
            getLogger().severe("Failed to save data.yml: " + e.getMessage());
        }
    }

    // ====== rLogin: эвристика "авторизован" ======
    // Идея: rLogin держит неавторизованных со слепотой и/или нулевым движением (по описанию). 2
    // Мы считаем, что игрок "уже авторизован", если:
    // - нет BLINDNESS
    // - и скорость ходьбы не нулевая
    private boolean looksAuthenticated(Player p) {
        boolean blind = p.hasPotionEffect(PotionEffectType.BLINDNESS);
        // WalkSpeed по умолчанию 0.2f. Некоторые плагины могут менять, но "0" — частый признак freeze.
        boolean canWalk = p.getWalkSpeed() > 0.0001f;
        return !blind && canWalk;
    }

    private void beginAuthWatch(Player p) {
        UUID id = p.getUniqueId();
        if (isCompleted(id) || started.contains(id)) return;

        waitingAuth.add(id);

        // каждые 10 тиков, максимум 10 секунд
        final int[] tries = {0};
        Bukkit.getScheduler().runTaskTimer(this, task -> {
            tries[0]++;

            if (!p.isOnline()) {
                waitingAuth.remove(id);
                task.cancel();
                return;
            }

            if (isCompleted(id) || started.contains(id)) {
                waitingAuth.remove(id);
                task.cancel();
                return;
            }

            if (looksAuthenticated(p)) {
                waitingAuth.remove(id);
                task.cancel();
                startQuiz(p);
                return;
            }

            if (tries[0] >= 20) { // 20 * 10 тиков = 200 тиков = 10 секунд
                // просто перестаем следить (может игрок не залогинился)
                task.cancel();
            }
        }, 10L, 10L);
    }

    // ====== QUIZ ======
    private void startQuiz(Player p) {
        UUID id = p.getUniqueId();
        if (started.contains(id) || isCompleted(id)) return;

        started.add(id);
        frozen.add(id);
        quizIndex.put(id, 0);

        p.sendMessage(cmsg("messages.start"));
        openQuizGui(p);
    }

    private void finishQuiz(Player p) {
        UUID id = p.getUniqueId();
        quizIndex.remove(id);
        frozen.remove(id);
        started.remove(id);

        setCompleted(p);
        p.sendMessage(cmsg("messages.done"));
        p.closeInventory();
    }

    private void restartQuizWrong(Player p) {
        quizIndex.put(p.getUniqueId(), 0);
        p.sendMessage(cmsg("messages.wrong"));

        // Кликабельная ссылка
        String text = getConfig().getString("link.text", "С правилами нужно ознакомиться на сайте");
        String url = getConfig().getString("link.url", "https://example.com/rules");
        String hover = getConfig().getString("link.hover", "Открыть правила");

        Component link = Component.text(ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', text)))
                .clickEvent(ClickEvent.openUrl(url))
                .hoverEvent(HoverEvent.showText(Component.text(ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', hover)))));

        p.sendMessage(link);

        openQuizGui(p);
    }

    private void nextQuestion(Player p) {
        int idx = quizIndex.getOrDefault(p.getUniqueId(), 0) + 1;
        if (idx >= questions.size()) {
            finishQuiz(p);
        } else {
            quizIndex.put(p.getUniqueId(), idx);
            openQuizGui(p);
        }
    }

    // ====== GUI билд ======
    private void openQuizGui(Player p) {
        int idx = quizIndex.getOrDefault(p.getUniqueId(), 0);
        Question q = questions.get(Math.max(0, Math.min(idx, questions.size() - 1)));

        String title = ChatColor.GOLD + "Правила: " + (idx + 1) + "/" + questions.size();
        Inventory inv = Bukkit.createInventory(p, GUI_SIZE, title);

        // Заполняем стеклом для красоты/защиты
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta fm = filler.getItemMeta();
        fm.setDisplayName(" ");
        filler.setItemMeta(fm);
        for (int i = 0; i < GUI_SIZE; i++) inv.setItem(i, filler);

        // Кнопка НЕТ
        inv.setItem(SLOT_NO, makeButton(Material.RED_WOOL, ChatColor.RED + "НЕТ", List.of(
                ChatColor.GRAY + "Нажми, чтобы ответить: " + ChatColor.RED + "нет"
        )));

        // Инфо (вопрос)
        inv.setItem(SLOT_INFO, makeButton(Material.BOOK, ChatColor.YELLOW + "Вопрос", List.of(
                ChatColor.WHITE + q.text(),
                "",
                ChatColor.GRAY + "Отвечай кнопками: " + ChatColor.GREEN + "ДА" + ChatColor.GRAY + " / " + ChatColor.RED + "НЕТ"
        )));

        // Кнопка ДА
        inv.setItem(SLOT_YES, makeButton(Material.LIME_WOOL, ChatColor.GREEN + "ДА", List.of(
                ChatColor.GRAY + "Нажми, чтобы ответить: " + ChatColor.GREEN + "да"
        )));

        // Открываем/обновляем
        p.openInventory(inv);
    }

    private ItemStack makeButton(Material mat, String name, List<String> lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta im = it.getItemMeta();
        im.setDisplayName(name);
        im.setLore(lore);
        it.setItemMeta(im);
        return it;
    }

    // ====== EVENTS ======

    // 1) На join начинаем "следить" — вдруг авто-логин по IP и игрок сразу авторизован
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();

        // если уже проходил — ничего
        if (isCompleted(id)) return;

        // чуть позже после входа
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!p.isOnline() || isCompleted(id) || started.contains(id)) return;

            // если уже выглядит авторизованным — стартуем
            if (looksAuthenticated(p)) startQuiz(p);
            else beginAuthWatch(p); // иначе — следим (например, пока он введёт /login)
        }, 40L);
    }

    // 2) Если игрок пишет /login — усиливаем проверку "после логина"
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();

        if (isCompleted(id) || started.contains(id)) return;

        String cmd = e.getMessage().toLowerCase(Locale.ROOT);
        if (cmd.startsWith("/login") || cmd.startsWith("/l ")
                || cmd.startsWith("/register") || cmd.startsWith("/reg")) {
            // после выполнения команды rLogin может снять freeze — начнем отслеживать
            beginAuthWatch(p);
        }
    }

    // Заморозка после авторизации (наш quiz)
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMove(PlayerMoveEvent e) {
        if (!frozen.contains(e.getPlayer().getUniqueId())) return;
        if (e.getTo() == null) return;

        if (e.getFrom().getX() != e.getTo().getX()
                || e.getFrom().getY() != e.getTo().getY()
                || e.getFrom().getZ() != e.getTo().getZ()) {
            e.setTo(e.getFrom());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && frozen.contains(p.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamageBy(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p && frozen.contains(p.getUniqueId())) e.setCancelled(true);
        if (e.getEntity() instanceof Player p && frozen.contains(p.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (frozen.contains(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (frozen.contains(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (frozen.contains(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    // Нельзя “закрыть” GUI и уйти играть
    @EventHandler
    public void onInventoryClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        if (!frozen.contains(p.getUniqueId())) return;

        // если тест активен — вернём GUI обратно
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (p.isOnline() && frozen.contains(p.getUniqueId())) openQuizGui(p);
        }, 1L);
    }

    // Клики по GUI
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        UUID id = p.getUniqueId();
        if (!frozen.contains(id)) return;

        // запрет перемещения предметов
        e.setCancelled(true);

        if (e.getClickedInventory() == null) return;
        if (!(e.getView().getTopInventory().getHolder() == p || e.getView().getTopInventory().getSize() == GUI_SIZE)) {
            // на всякий
        }

        int slot = e.getRawSlot();
        if (slot != SLOT_YES && slot != SLOT_NO) return;

        int idx = quizIndex.getOrDefault(id, 0);
        Question q = questions.get(Math.max(0, Math.min(idx, questions.size() - 1)));

        String answer = (slot == SLOT_YES) ? "да" : "нет";
        if (answer.equals(q.correct())) {
            p.sendMessage(cmsg("messages.correct"));
            nextQuestion(p);
        } else {
            // сброс на первый вопрос + ссылка
            restartQuizWrong(p);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        frozen.remove(id);
        quizIndex.remove(id);
        started.remove(id);
        waitingAuth.remove(id);
    }

    // ====== ADMIN COMMAND ======
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 2 && args[0].equalsIgnoreCase("reset")) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Игрок не найден онлайн.");
                return true;
            }
            data.set("players." + target.getUniqueId(), null);
            saveData();
            sender.sendMessage(ChatColor.GREEN + "Сбросил прохождение для " + target.getName());
            return true;
        }
        sender.sendMessage(ChatColor.YELLOW + "Использование: /rulesquiz reset <player>");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("reset");
        if (args.length == 2 && args[0].equalsIgnoreCase("reset")) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
            return names;
        }
        return List.of();
    }

    private record Question(String text, String correct, String explain) {}
}