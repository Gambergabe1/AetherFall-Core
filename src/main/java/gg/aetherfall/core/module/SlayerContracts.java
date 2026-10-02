package gg.aetherfall.core.module;
import gg.aetherfall.core.AetherCore;
import gg.aetherfall.core.data.PlayerData;
import gg.aetherfall.core.util.Text;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler; import org.bukkit.event.Listener; import org.bukkit.event.entity.EntityDeathEvent;
import java.util.Locale;
public final class SlayerContracts implements Listener {
 private final AetherCore plugin; public SlayerContracts(AetherCore p){plugin=p;}
 public void open(Player p){PlayerData d=plugin.data().get(p); if(d==null)return; p.sendMessage(Text.mm("<dark_red><bold>Slayer Contract</bold></dark_red> <gray>Target: <yellow>"+(d.slayerType.isBlank()?"none":d.slayerType)+"</yellow> · Progress: <white>"+d.slayerKills+"</white> · Tier <gold>"+d.slayerTier+"</gold>")); p.sendMessage(Text.mm("<gray>Start one with <yellow>/slayer start zombie|spider|skeleton</yellow>"));}
 public void start(Player p,String type){PlayerData d=plugin.data().get(p); if(d==null)return; type=type.toLowerCase(Locale.ROOT); if(!java.util.List.of("zombie","spider","skeleton").contains(type)){p.sendMessage(Text.mm("<red>Targets: zombie, spider, skeleton"));return;} d.slayerType=type;d.slayerKills=0; p.sendMessage(Text.mm("<green>Slayer contract started: hunt " + type + "s."));plugin.data().saveAsync(d);}
 @EventHandler public void death(EntityDeathEvent e){Player p=e.getEntity().getKiller();if(p==null)return;PlayerData d=plugin.data().get(p);if(d==null||d.slayerType.isBlank())return;String type=e.getEntity().getType().name().toLowerCase(Locale.ROOT);if(!type.contains(d.slayerType))return;d.slayerKills++;int needed=10+(d.slayerTier*10);if(d.slayerKills>=needed){d.slayerTier++;d.slayerKills=0;plugin.giveCoins(p,300L*d.slayerTier);p.sendMessage(Text.mm("<gold>Slayer tier complete!</gold> <gray>Tier "+d.slayerTier+" reward: <yellow>"+(300*d.slayerTier)+" coins"));}plugin.data().saveAsync(d);}
}
