package com.glodblock.github.extendedae.common.tileentities;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.config.PowerUnits;
import appeng.api.crafting.IPatternDetails;
import appeng.api.implementations.blockentities.ICraftingMachine;
import appeng.api.implementations.blockentities.PatternContainerGroup;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.IGridNode;
import appeng.api.networking.ticking.IGridTickable;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.networking.ticking.TickingRequest;
import appeng.api.orientation.BlockOrientation;
import appeng.api.orientation.RelativeSide;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.blockentity.grid.AENetworkPowerBlockEntity;
import appeng.crafting.pattern.AEProcessingPattern;
import appeng.helpers.externalstorage.GenericStackInv;
import appeng.util.inv.AppEngInternalInventory;
import com.glodblock.github.extendedae.common.EPPItemAndBlock;
import com.glodblock.github.extendedae.xmod.ExternalTypes;
import com.glodblock.github.glodium.util.GlodUtil;
import com.google.common.primitives.Ints;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import mekanism.api.Action;
import mekanism.api.chemical.gas.Gas;
import mekanism.api.chemical.gas.GasStack;
import mekanism.common.capabilities.Capabilities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fluids.capability.IFluidHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import vazkii.botania.api.BotaniaForgeCapabilities;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public class TileCaner extends AENetworkPowerBlockEntity implements IGridTickable, ICraftingMachine {

    private static final Reference2ObjectMap<AEKeyType, ToCan<?>> CAN_MAP = new Reference2ObjectOpenHashMap<>();

    static {
        CAN_MAP.put(AEKeyType.fluids(), new ToCan<>(ForgeCapabilities.FLUID_HANDLER_ITEM, AEKeyType.fluids()) {
            @Override
            long fill(ItemStack stack, long amount, AEKey stuff) {
                var cap = this.getCap(stack);
                if (cap != null) {
                    var filled = cap.fill(((AEFluidKey) stuff).toStack(Ints.saturatedCast(amount)), IFluidHandler.FluidAction.EXECUTE);
                    this.result = cap.getContainer();
                    return amount - filled;
                }
                return amount;
            }
        });
        if (ExternalTypes.FLUX != null) {
            CAN_MAP.put(ExternalTypes.FLUX, new ToCan<>(ForgeCapabilities.ENERGY, ExternalTypes.FLUX) {
                @Override
                long fill(ItemStack stack, long amount, AEKey stuff) {
                    var cap = this.getCap(stack);
                    if (cap != null) {
                        int added = cap.receiveEnergy(Ints.saturatedCast(amount), false);
                        this.result = stack.copy();
                        return amount - added;
                    }
                    return amount;
                }
            });
        }
        if (ExternalTypes.MANA != null) {
            CAN_MAP.put(ExternalTypes.MANA, new ToCan<>(BotaniaForgeCapabilities.MANA_ITEM, ExternalTypes.MANA) {
                @Override
                long fill(ItemStack stack, long amount, AEKey stuff) {
                    var cap = this.getCap(stack);
                    if (cap != null) {
                        int origin = cap.getMana();
                        cap.addMana(Ints.saturatedCast(amount));
                        int now = cap.getMana();
                        int added = now - origin;
                        this.result = stack.copy();
                        return amount - added;
                    }
                    return amount;
                }
            });
        }
        if (ExternalTypes.GAS != null) {
            CAN_MAP.put(ExternalTypes.GAS, new ToCan<>(Capabilities.GAS_HANDLER, ExternalTypes.GAS) {
                @Override
                long fill(ItemStack stack, long amount, AEKey stuff) {
                    var cap = this.getCap(stack);
                    if (cap != null && stuff.getPrimaryKey() instanceof Gas gas) {
                        var left = cap.insertChemical(new GasStack(gas, amount), Action.EXECUTE);
                        this.result = stack.copy();
                        return left.getAmount();
                    }
                    return amount;
                }
            });
        }
    }

    public static final int POWER_MAXIMUM_AMOUNT = 3200;
    public static final int POWER_USAGE = 800;
    private final AppEngInternalInventory container = new AppEngInternalInventory(this, 1, 1);
    private final GenericStackInv stuff = new GenericStackInv(this::wake, 1);
    private ItemStack target = ItemStack.EMPTY;
    private Direction ejectSide = null;

    public TileCaner(BlockPos pos, BlockState blockState) {
        super(GlodUtil.getTileType(TileCaner.class, TileCaner::new, EPPItemAndBlock.CANER), pos, blockState);
        // don't let container item go into it
        this.stuff.setCapacity(AEKeyType.items(), 0);
        this.getMainNode()
                .setFlags()
                .setIdlePowerUsage(0)
                .addService(IGridTickable.class, this);
        this.setInternalMaxPower(POWER_MAXIMUM_AMOUNT);
        this.setPowerSides(getGridConnectableSides(getOrientation()));
    }

    public AppEngInternalInventory getContainer() {
        return this.container;
    }

    public GenericStackInv getStuff() {
        return this.stuff;
    }

    @Nullable
    private ToCan<?> getAndCast(AEKeyType type) {
        if (CAN_MAP.containsKey(type)) {
            return CAN_MAP.get(type);
        }
        return null;
    }

    public boolean isDone() {
        if (!this.target.isEmpty()) {
            return this.target.equals(this.container.getStackInSlot(0), false);
        }
        return false;
    }

    private void eject() {
        if (this.level != null && !this.container.getStackInSlot(0).isEmpty()) {
            if (this.ejectSide != null) {
                var target = InternalInventory.wrapExternal(this.level, this.getBlockPos().relative(this.ejectSide), this.ejectSide.getOpposite());
                if (target != null) {
                    int startItems = this.container.getStackInSlot(0).getCount();
                    this.container.insertItem(0, target.addItems(this.container.extractItem(0, 64, false)), false);
                    int endItems = this.container.getStackInSlot(0).getCount();
                    if (startItems != endItems) {
                        this.target = ItemStack.EMPTY;
                    }
                }
            }
        }
    }

    @Override
    public Set<Direction> getGridConnectableSides(BlockOrientation orientation) {
        return EnumSet.of(orientation.getSide(RelativeSide.TOP), orientation.getSide(RelativeSide.BOTTOM));
    }

    @Override
    protected void onOrientationChanged(BlockOrientation orientation) {
        super.onOrientationChanged(orientation);
        this.setPowerSides(getGridConnectableSides(orientation));
    }

    @Override
    public <T> @NotNull LazyOptional<T> getCapability(Capability<T> capability, Direction facing) {
        if (appeng.capabilities.Capabilities.CRAFTING_MACHINE == capability) {
            return appeng.capabilities.Capabilities.CRAFTING_MACHINE.orEmpty(capability, LazyOptional.of(() -> this));
        }
        if (capability == appeng.capabilities.Capabilities.GENERIC_INTERNAL_INV) {
            return LazyOptional.of(() -> this.stuff).cast();
        }
        return super.getCapability(capability, facing);
    }

    @Override
    public TickingRequest getTickingRequest(IGridNode node) {
        return new TickingRequest(1, 1, !hasJob(), true);
    }

    @Override
    public TickRateModulation tickingRequest(IGridNode node, int ticksSinceLastCall) {
        this.markForUpdate();
        if (this.getInternalCurrentPower() < POWER_MAXIMUM_AMOUNT) {
            getMainNode().ifPresent(grid -> {
                double toExtract = Math.min(POWER_USAGE, this.getInternalMaxPower() - this.getInternalCurrentPower());
                final double extracted = grid.getEnergyService().extractAEPower(toExtract, Actionable.MODULATE, PowerMultiplier.ONE);
                this.injectExternalPower(PowerUnits.AE, extracted, Actionable.MODULATE);
            });
        }
        this.fill();
        if (this.isDone()) {
            this.eject();
        }
        return TickRateModulation.FASTER;
    }

    @Override
    public InternalInventory getInternalInventory() {
        return this.container;
    }

    @Override
    public void onChangeInventory(InternalInventory inv, int slot) {
        if (inv == this.container) {
            this.wake();
        }
    }

    private void wake() {
        getMainNode().ifPresent((grid, node) -> grid.getTickManager().alertDevice(node));
    }

    @Override
    public void saveAdditional(CompoundTag data) {
        super.saveAdditional(data);
        this.stuff.writeToChildTag(data, "stuff");
        data.put("target", this.target.save(new CompoundTag()));
        if (this.ejectSide != null) {
            data.putString("ejectSide", this.ejectSide.name());
        }
    }

    @Override
    public void loadTag(CompoundTag data) {
        super.loadTag(data);
        this.stuff.readFromChildTag(data, "stuff");
        if (data.contains("target")) {
            this.target = ItemStack.of(data.getCompound("target"));
        }
        if (data.contains("ejectSide")) {
            this.ejectSide = Direction.valueOf(data.getString("ejectSide"));
        }
    }

    @Override
    public void addAdditionalDrops(Level level, BlockPos pos, List<ItemStack> drops) {
        super.addAdditionalDrops(level, pos, drops);
        var stack = this.stuff.getStack(0);
        if (stack != null) {
            stack.what().addDrops(stack.amount(), drops, level, pos);
        }
    }

    @Override
    protected boolean readFromStream(FriendlyByteBuf data) {
        var changed = super.readFromStream(data);
        this.container.setItemDirect(0, data.readItem());
        return changed;
    }

    @Override
    protected void writeToStream(FriendlyByteBuf data) {
        super.writeToStream(data);
        data.writeItem(this.container.getStackInSlot(0));
    }

    private void fill() {
        var stack = this.container.getStackInSlot(0);
        var obj = this.stuff.getStack(0);
        if (stack.isEmpty() || obj == null) {
            return;
        }
        var handler = this.getAndCast(obj.what().getType());
        if (handler == null || !handler.isValid(stack, obj.what())) {
            return;
        }
        long origin = obj.amount();
        if (this.getInternalCurrentPower() >= POWER_USAGE) {
            long now = handler.fill(stack, obj.amount(), obj.what());
            if (origin != now) {
                this.stuff.extract(0, obj.what(), origin - now, Actionable.MODULATE);
                this.container.setItemDirect(0, handler.getResult());
                this.extractAEPower(POWER_USAGE, Actionable.MODULATE, PowerMultiplier.CONFIG);
            }
        }
    }

    private boolean hasJob() {
        return this.stuff.getStack(0) != null && !this.container.getStackInSlot(0).isEmpty();
    }

    @Override
    public PatternContainerGroup getCraftingMachineInfo() {
        Component name = this.hasCustomName() ? this.getCustomName() : EPPItemAndBlock.CANER.asItem().getDescription();
        return new PatternContainerGroup(AEItemKey.of(EPPItemAndBlock.CANER), name, List.of());
    }

    @Override
    public boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputs, Direction ejectionDirection) {
        if (patternDetails instanceof AEProcessingPattern) {
            if (this.stuff.getStack(0) == null && this.container.getStackInSlot(0).isEmpty()) {
                if (inputs.length == 2) {
                    if (inputs[0].getFirstEntry() != null && inputs[1].getFirstEntry() != null) {
                        var obj = inputs[0].getFirstEntry();
                        var cnt = inputs[1].getFirstEntry();
                        var rst = patternDetails.getPrimaryOutput();
                        if (obj.getKey() instanceof AEItemKey) {
                            obj = inputs[1].getFirstEntry();
                            cnt = inputs[0].getFirstEntry();
                        }
                        // sanity check
                        if (!(cnt.getKey() instanceof AEItemKey) || cnt.getLongValue() != 1) {
                            return false;
                        }
                        if (!(rst.what() instanceof AEItemKey) || rst.amount() != 1) {
                            return false;
                        }
                        // try to fill
                        this.stuff.setStack(0, new GenericStack(obj.getKey(), obj.getLongValue()));
                        this.container.setItemDirect(0, ((AEItemKey) cnt.getKey()).toStack());
                        // check success
                        boolean fail = this.stuff.getStack(0) == null || this.stuff.getStack(0).amount() != obj.getLongValue();
                        if (this.container.getStackInSlot(0).isEmpty()) {
                            fail = true;
                        }
                        // roll back
                        if (fail) {
                            this.stuff.setStack(0, null);
                            this.container.setItemDirect(0, ItemStack.EMPTY);
                            return false;
                        } else {
                            this.target = ((AEItemKey) rst.what()).toStack();
                            this.ejectSide = ejectionDirection;
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    @Override
    public boolean acceptsPlans() {
        return true;
    }

    abstract static class ToCan<T> {

        final Capability<T> cap;
        final AEKeyType type;
        ItemStack result = ItemStack.EMPTY;

        ToCan(Capability<T> cap, AEKeyType type) {
            this.cap = cap;
            this.type = type;
        }

        boolean isValid(ItemStack stack, AEKey stuff) {
            return stuff.getType() == this.type && stack.getCapability(this.cap).isPresent();
        }

        T getCap(ItemStack stack) {
            return stack.getCapability(this.cap).resolve().orElse(null);
        }

        ItemStack getResult() {
            return this.result;
        }

        // Return how much left.
        abstract long fill(ItemStack stack, long amount, AEKey type);

    }

}
