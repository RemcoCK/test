package nl.nerdminecraft.maxtotem.mixin;

import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin {

    @Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
    private void nerdMaxOneTotem$blockSecondTotem(Player player, CallbackInfo ci) {
        ItemEntity itemEntity = (ItemEntity) (Object) this;
        ItemStack groundStack = itemEntity.getItem();

        // Only change pickup behavior for Totems of Undying.
        if (!groundStack.is(Items.TOTEM_OF_UNDYING)) {
            return;
        }

        // Inventory.contains(Predicate) checks the player's carried inventory,
        // including the equipment/offhand-backed inventory slots in current 26.3.
        boolean alreadyHasTotem = player.getInventory()
                .contains(stack -> stack.is(Items.TOTEM_OF_UNDYING));

        if (alreadyHasTotem) {
            // Cancelling at HEAD means the second totem stays on the ground.
            // Once the player's current totem is consumed/lost, they can pick it up.
            ci.cancel();
        }
    }
}
