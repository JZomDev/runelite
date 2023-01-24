/*
 * Copyright (c) 2018, TheLonelyDev <https://github.com/TheLonelyDev>
 * Copyright (c) 2018, Jeremy Plsek <https://github.com/jplsek>
 * Copyright (c) 2019, Hydrox6 <ikada@protonmail.ch>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.bank;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.HashMultiset;
import com.google.common.collect.Multiset;
import com.google.inject.Provides;
import java.awt.event.KeyEvent;
import java.text.ParseException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.ItemID;
import net.runelite.api.MenuEntry;
import net.runelite.api.ScriptID;
import net.runelite.api.SpriteID;
import net.runelite.api.VarClientInt;
import net.runelite.api.VarClientStr;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuShouldLeftClick;
import net.runelite.api.events.ScriptCallbackEvent;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetID;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.Keybind;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.QuantityFormatter;

@PluginDescriptor(
	name = "Bank",
	description = "Modifications to the banking interface",
	tags = {"grand", "exchange", "high", "alchemy", "prices", "deposit", "pin"}
)
@Slf4j
public class BankPlugin extends Plugin
{
	private static final String DEPOSIT_WORN = "Deposit worn items";
	private static final String DEPOSIT_INVENTORY = "Deposit inventory";
	private static final String DEPOSIT_LOOT = "Deposit loot";
	private static final String TOGGLE_PLACEHOLDERS = "Always set placeholders";
	private static final String SEED_VAULT_TITLE = "Seed Vault";

	private static final int ITEMS_PER_ROW = 8;
	private static final int ITEM_VERTICAL_SPACING = 36;
	private static final int ITEM_HORIZONTAL_SPACING = 48;
	private static final int ITEM_ROW_START = 51;

	private static final String NUMBER_REGEX = "[0-9]+(\\.[0-9]+)?[kmb]?";
	private static final String ORDER_REGEX = "(a|d|asc|desc?)?";

	private static final Pattern VALUE_SEARCH_PATTERN = Pattern.compile("^(?<mode>qty|ge|ha|alch)?" +
		" *(?<individual>i|iv|individual|per)?" +
		" *(((?<op>[<>=]|>=|<=) *(?<num>" + NUMBER_REGEX + ")) *(?<order>" + ORDER_REGEX + "))" +
		"|(((?<num1>" + NUMBER_REGEX + ") *- *(?<num2>" + NUMBER_REGEX + ")) *(?<order2>" + ORDER_REGEX + "))$", Pattern.CASE_INSENSITIVE);

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ItemManager itemManager;

	@Inject
	private BankConfig config;

	@Inject
	private BankSearch bankSearch;

	@Inject
	private KeyManager keyManager;

	private boolean forceRightClickFlag;
	private Multiset<Integer> itemQuantities; // bank item quantities for bank value search
	private String searchString;
	private int orderType;

	private final KeyListener searchHotkeyListener = new KeyListener()
	{
		@Override
		public void keyTyped(KeyEvent e)
		{
		}

		@Override
		public void keyPressed(KeyEvent e)
		{
			Keybind keybind = config.searchKeybind();
			if (keybind.matches(e))
			{
				Widget bankContainer = client.getWidget(WidgetInfo.BANK_ITEM_CONTAINER);
				if (bankContainer == null || bankContainer.isSelfHidden())
				{
					return;
				}

				log.debug("Search hotkey pressed");

				bankSearch.initSearch();
				e.consume();
			}
		}

		@Override
		public void keyReleased(KeyEvent e)
		{
		}
	};

	@Provides
	BankConfig getConfig(ConfigManager configManager)
	{
		return configManager.getConfig(BankConfig.class);
	}

	@Override
	protected void startUp()
	{
		keyManager.registerKeyListener(searchHotkeyListener);
	}

	@Override
	protected void shutDown()
	{
		keyManager.unregisterKeyListener(searchHotkeyListener);
		clientThread.invokeLater(() -> bankSearch.reset(false));
		forceRightClickFlag = false;
		itemQuantities = null;
		searchString = null;
	}

	@Subscribe
	public void onMenuShouldLeftClick(MenuShouldLeftClick event)
	{
		if (!forceRightClickFlag)
		{
			return;
		}

		forceRightClickFlag = false;
		MenuEntry[] menuEntries = client.getMenuEntries();
		for (MenuEntry entry : menuEntries)
		{

			if ((entry.getOption().equals(DEPOSIT_WORN) && config.rightClickBankEquip())
				|| (entry.getOption().equals(DEPOSIT_INVENTORY) && config.rightClickBankInventory())
				|| (entry.getOption().equals(DEPOSIT_LOOT) && config.rightClickBankLoot())
				|| (entry.getTarget().contains(TOGGLE_PLACEHOLDERS) && config.rightClickPlaceholders())
			)
			{
				event.setForceRightClick(true);
				return;
			}
		}
	}

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		if ((event.getOption().equals(DEPOSIT_WORN) && config.rightClickBankEquip())
			|| (event.getOption().equals(DEPOSIT_INVENTORY) && config.rightClickBankInventory())
			|| (event.getOption().equals(DEPOSIT_LOOT) && config.rightClickBankLoot())
			|| (event.getTarget().contains(TOGGLE_PLACEHOLDERS) && config.rightClickPlaceholders()))
		{
			forceRightClickFlag = true;
		}
	}

	@Subscribe
	public void onScriptCallbackEvent(ScriptCallbackEvent event)
	{
		int[] intStack = client.getIntStack();
		String[] stringStack = client.getStringStack();
		int intStackSize = client.getIntStackSize();
		int stringStackSize = client.getStringStackSize();

		switch (event.getEventName())
		{
			case "bankSearchFilter":
				int itemId = intStack[intStackSize - 1];
				String search = stringStack[stringStackSize - 1];

				if (valueSearch(itemId, search))
				{
					// return true
					intStack[intStackSize - 2] = 1;
				}

				break;
			case "bankpinButtonSetup":
			{
				if (!config.bankPinKeyboard())
				{
					return;
				}

				final int compId = intStack[intStackSize - 2];
				final int buttonId = intStack[intStackSize - 1];
				Widget button = client.getWidget(compId);
				Widget buttonRect = button.getChild(0);

				final Object[] onOpListener = buttonRect.getOnOpListener();
				buttonRect.setOnKeyListener((JavaScriptCallback) e ->
				{
					int typedChar = e.getTypedKeyChar() - '0';
					if (typedChar != buttonId)
					{
						return;
					}

					log.debug("Bank pin keypress");

					client.runScript(onOpListener);
					// Block the key press this tick in keypress_permit so it doesn't enter the chatbox
					client.setVarcIntValue(VarClientInt.BLOCK_KEYPRESS, client.getGameCycle() + 1);
				});
				break;
			}
		}
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (event.getGroupId() != WidgetID.SEED_VAULT_GROUP_ID || !config.seedVaultValue())
		{
			return;
		}

		updateSeedVaultTotal();
	}

	@Subscribe
	public void onScriptPostFired(ScriptPostFired event)
	{
		if (event.getScriptId() == ScriptID.BANKMAIN_BUILD)
		{
			ContainerPrices price = getWidgetContainerPrices(WidgetInfo.BANK_ITEM_CONTAINER, InventoryID.BANK);
			if (price == null)
			{
				return;
			}

			Widget bankTitle = client.getWidget(WidgetInfo.BANK_TITLE_BAR);
			bankTitle.setText(bankTitle.getText() + createValueText(price.getGePrice(), price.getHighAlchPrice()));
			orderBank();
		}
		else if (event.getScriptId() == ScriptID.BANKMAIN_SEARCH_REFRESH)
		{
			// vanilla only lays out the bank every 40 client ticks, so if the search input has changed,
			// and the bank wasn't laid out this tick, lay it out early
			final String inputText = client.getVarcStrValue(VarClientStr.INPUT_TEXT);
			if (searchString != inputText && client.getGameCycle() % 40 != 0)
			{
				clientThread.invokeLater(bankSearch::layoutBank);
				searchString = inputText;
			}
		}
		else if (event.getScriptId() == ScriptID.GROUP_IRONMAN_STORAGE_BUILD)
		{
			ContainerPrices price = getWidgetContainerPrices(WidgetInfo.GROUP_STORAGE_ITEM_CONTAINER, InventoryID.GROUP_STORAGE);
			if (price == null)
			{
				return;
			}

			Widget bankTitle = client.getWidget(WidgetInfo.GROUP_STORAGE_UI).getChild(1);
			bankTitle.setText(bankTitle.getText() + createValueText(price.getGePrice(), price.getHighAlchPrice()));
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		int containerId = event.getContainerId();

		if (containerId == InventoryID.BANK.getId())
		{
			itemQuantities = null;
		}
		else if (containerId == InventoryID.SEED_VAULT.getId() && config.seedVaultValue())
		{
			updateSeedVaultTotal();
		}
	}

	private void orderBank()
	{
		Widget itemContainer = client.getWidget(WidgetInfo.BANK_ITEM_CONTAINER);
		if (itemContainer == null)
		{
			return;
		}

		int items = 0;

		Widget[] containerChildren = itemContainer.getDynamicChildren();

		if (orderType == -1)
		{
			Arrays.sort(containerChildren, Comparator.comparingLong((widget) ->
			{
				Widget w = (Widget) widget;
				long price = Math.max((long) itemManager.getItemComposition(w.getItemId()).getHaPrice(), (long) itemManager.getItemPrice(w.getItemId())) * w.getItemQuantity();
				if (w.isHidden() || itemManager.getItemComposition(w.getItemId()).getPlaceholderTemplateId() == 14401)
				{
					price = 0;
				}
				return price;
			}).reversed());
		}
		else if (orderType == 1)
		{
			Arrays.sort(containerChildren, Comparator.comparingLong((widget) ->
			{
				Widget w = (Widget) widget;
				long price = Math.max((long) itemManager.getItemComposition(w.getItemId()).getHaPrice(), (long) itemManager.getItemPrice(w.getItemId())) * w.getItemQuantity();
				if (w.isHidden() || itemManager.getItemComposition(w.getItemId()).getPlaceholderTemplateId() == 14401)
				{
					price = 0;
				}
				return price;
			}));
		}
		else
		{
			Arrays.sort(containerChildren, Comparator.comparingInt(Widget::getOriginalY)
				.thenComparingInt(Widget::getOriginalX));
		}

		// sort the child array as the items are not in the displayed order
		for (Widget child : containerChildren)
		{
			if (child.getItemId() != -1 && !child.isHidden())
			{
				// calculate correct item position as if this was a normal tab
				int adjYOffset = (items / ITEMS_PER_ROW) * ITEM_VERTICAL_SPACING;
				int adjXOffset = (items % ITEMS_PER_ROW) * ITEM_HORIZONTAL_SPACING + ITEM_ROW_START;

				if (child.getOriginalY() != adjYOffset || child.getOriginalX() != adjXOffset)
				{
					child.setOriginalY(adjYOffset);
					child.setOriginalX(adjXOffset);
					child.revalidate();
				}

				items++;
			}

			// separator line or tab text
			if (child.getSpriteId() == SpriteID.RESIZEABLE_MODE_SIDE_PANEL_BACKGROUND
				|| child.getText().contains("Tab"))
			{
				child.setHidden(true);
			}
		}
	}

	private String createValueText(long gePrice, long haPrice)
	{
		StringBuilder stringBuilder = new StringBuilder();
		if (config.showGE() && gePrice != 0)
		{
			stringBuilder.append(" (");

			if (config.showHA())
			{
				stringBuilder.append("GE: ");
			}

			if (config.showExact())
			{
				stringBuilder.append(QuantityFormatter.formatNumber(gePrice));
			}
			else
			{
				stringBuilder.append(QuantityFormatter.quantityToStackSize(gePrice));
			}
			stringBuilder.append(')');
		}

		if (config.showHA() && haPrice != 0)
		{
			stringBuilder.append(" (");

			if (config.showGE())
			{
				stringBuilder.append("HA: ");
			}

			if (config.showExact())
			{
				stringBuilder.append(QuantityFormatter.formatNumber(haPrice));
			}
			else
			{
				stringBuilder.append(QuantityFormatter.quantityToStackSize(haPrice));
			}
			stringBuilder.append(')');
		}

		return stringBuilder.toString();
	}

	private void updateSeedVaultTotal()
	{
		final Widget titleContainer = client.getWidget(WidgetInfo.SEED_VAULT_TITLE_CONTAINER);
		if (titleContainer == null)
		{
			return;
		}

		final Widget title = titleContainer.getChild(1);
		if (title == null)
		{
			return;
		}

		final ContainerPrices prices = calculate(getSeedVaultItems());
		if (prices == null)
		{
			return;
		}

		final String titleText = createValueText(prices.getGePrice(), prices.getHighAlchPrice());
		title.setText(SEED_VAULT_TITLE + titleText);
	}

	private Item[] getSeedVaultItems()
	{
		final ItemContainer itemContainer = client.getItemContainer(InventoryID.SEED_VAULT);
		if (itemContainer == null)
		{
			return null;
		}

		return itemContainer.getItems();
	}

	@VisibleForTesting
	boolean valueSearch(final int itemId, final String str)
	{
		final Matcher matcher = VALUE_SEARCH_PATTERN.matcher(str);
		if (!matcher.matches())
		{
			return false;
		}

		// Count bank items and remember it for determining item quantity
		if (itemQuantities == null)
		{
			itemQuantities = getBankItemSet();
		}

		final ItemComposition itemComposition = itemManager.getItemComposition(itemId);
		final int qty = matcher.group("individual") != null ? 1 : itemQuantities.count(itemId);
		final long gePrice = (long) itemManager.getItemPrice(itemId) * qty;
		final long haPrice = (long) itemComposition.getHaPrice() * qty;
		final boolean isPlaceholder = itemComposition.getPlaceholderTemplateId() != -1;

		long value = Math.max(gePrice, haPrice);

		final String mode = matcher.group("mode");
		if (mode != null)
		{
			switch (mode.toLowerCase())
			{
				case "qty":
					value = isPlaceholder ? 0 : qty;
					break;
				case "ge":
					value = gePrice;
					break;
				case "ha":
				case "alch":
					value = haPrice;
					break;
			}
		}

		String order = matcher.group("order");
		if (order != null)
		{
			switch (order)
			{
				case "a":
				case "asc":
					orderType = 1;
					break;
				case "d":
				case "des":
				case "desc":
					orderType = -1;
					break;
				default:
					orderType = 0;
			}
		}

		final String op = matcher.group("op");
		if (op != null)
		{
			long compare;
			try
			{
				compare = QuantityFormatter.parseQuantity(matcher.group("num"));
			}
			catch (ParseException e)
			{
				return false;
			}

			switch (op)
			{
				case ">":
					return value > compare;
				case "<":
					return value < compare;
				case "=":
					return value == compare;
				case ">=":
					return value >= compare;
				case "<=":
					return value <= compare;
			}
		}


		order = matcher.group("order2");
		if (order != null)
		{
			switch (order)
			{
				case "a":
				case "asc":
					orderType = 1;
					break;
				case "d":
				case "des":
				case "desc":
					orderType = -1;
					break;
				default:
					orderType = 0;
			}
		}

		final String num1 = matcher.group("num1");
		final String num2 = matcher.group("num2");
		if (num1 != null && num2 != null)
		{
			long compare1, compare2;
			try
			{
				compare1 = QuantityFormatter.parseQuantity(num1);
				compare2 = QuantityFormatter.parseQuantity(num2);
			}
			catch (ParseException e)
			{
				return false;
			}

			return compare1 <= value && compare2 >= value;
		}

		return false;
	}

	private Multiset<Integer> getBankItemSet()
	{
		ItemContainer itemContainer = client.getItemContainer(InventoryID.BANK);
		if (itemContainer == null)
		{
			return HashMultiset.create();
		}

		Multiset<Integer> set = HashMultiset.create();
		for (Item item : itemContainer.getItems())
		{
			if (item.getId() != ItemID.BANK_FILLER)
			{
				set.add(item.getId(), item.getQuantity());
			}
		}
		return set;
	}

	@Nullable
	ContainerPrices calculate(@Nullable Item[] items)
	{
		if (items == null)
		{
			return null;
		}

		long ge = 0;
		long alch = 0;

		for (final Item item : items)
		{
			final int qty = item.getQuantity();
			final int id = item.getId();

			if (id <= 0 || qty == 0)
			{
				continue;
			}

			alch += (long) getHaPrice(id) * qty;
			ge += (long) itemManager.getItemPrice(id) * qty;
		}

		return new ContainerPrices(ge, alch);
	}

	private int getHaPrice(int itemId)
	{
		switch (itemId)
		{
			case ItemID.COINS_995:
				return 1;
			case ItemID.PLATINUM_TOKEN:
				return 1000;
			default:
				return itemManager.getItemComposition(itemId).getHaPrice();
		}
	}

	private ContainerPrices getWidgetContainerPrices(WidgetInfo widgetInfo, InventoryID inventoryID)
	{
		final Widget widget = client.getWidget(widgetInfo);
		final ItemContainer itemContainer = client.getItemContainer(inventoryID);
		final Widget[] children = widget.getChildren();
		ContainerPrices prices = null;

		if (itemContainer != null && children != null)
		{
			long geTotal = 0, haTotal = 0;
			log.debug("Computing bank price of {} items", itemContainer.size());

			// In the bank, the first components are the bank items, followed by tabs etc. There are always 816 components regardless
			// of bank size, but we only need to check up to the bank size.
			for (int i = 0; i < itemContainer.size(); ++i)
			{
				Widget child = children[i];
				if (child != null && !child.isSelfHidden() && child.getItemId() > -1)
				{
					final int alchPrice = getHaPrice(child.getItemId());
					geTotal += (long) itemManager.getItemPrice(child.getItemId()) * child.getItemQuantity();
					haTotal += (long) alchPrice * child.getItemQuantity();
				}
			}

			prices = new ContainerPrices(geTotal, haTotal);
		}

		return prices;
	}
}