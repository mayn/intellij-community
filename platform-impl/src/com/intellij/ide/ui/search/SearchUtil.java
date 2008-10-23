/*
 * Copyright (c) 2000-2006 JetBrains s.r.o. All Rights Reserved.
 */

package com.intellij.ide.ui.search;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.MasterDetails;
import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.openapi.options.ConfigurableGroup;
import com.intellij.openapi.options.ex.GlassPanel;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.*;
import com.intellij.util.Alarm;
import com.intellij.util.Consumer;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.*;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * User: anna
 * Date: 07-Feb-2006
 */
public class SearchUtil {
  private static final Pattern HTML_PATTERN = Pattern.compile("<[^<>]*>");
  private static final Pattern QUOTED = Pattern.compile("\\\"([^\\\"]+)\\\"");

  private SearchUtil() {
  }

  public static void processProjectConfigurables(Project project, HashMap<SearchableConfigurable, TreeSet<OptionDescription>> options) {
    processConfigurables(project.getExtensions(Configurable.PROJECT_CONFIGURABLES), options);
    processConfigurables(project.getComponents(Configurable.class), options);
    final Application app = ApplicationManager.getApplication();
    processConfigurables(app.getExtensions(Configurable.APPLICATION_CONFIGURABLES), options);
    processConfigurables(app.getComponents(Configurable.class), options);
  }

  private static void processConfigurables(final Configurable[] configurables,
                                          final HashMap<SearchableConfigurable, TreeSet<OptionDescription>> options) {
    for (Configurable configurable : configurables) {
      if (configurable instanceof SearchableConfigurable) {
        TreeSet<OptionDescription> configurableOptions = new TreeSet<OptionDescription>();
        options.put((SearchableConfigurable)configurable, configurableOptions);
        if (configurable instanceof Configurable.Composite) {
          final Configurable[] children = ((Configurable.Composite)configurable).getConfigurables();
          processConfigurables(children, options);
        }

        if (configurable instanceof MasterDetails) {
          final MasterDetails md = (MasterDetails)configurable;
          md.initUi();
          _processComponent(configurable, configurableOptions, md.getMaster());
          _processComponent(configurable, configurableOptions, md.getDetails().getComponent());
        }
        else {
          _processComponent(configurable, configurableOptions, configurable.createComponent());
        }
      }
    }
  }

  private static void _processComponent(final Configurable configurable, final TreeSet<OptionDescription> configurableOptions,
                                        final JComponent component) {

    if (component == null) return;

    processUILabel(configurable.getDisplayName(), configurableOptions, null);
    processComponent(component, configurableOptions, null);
  }

  public static void processComponent(final JComponent component, final Set<OptionDescription> configurableOptions, @NonNls String path) {
    final Border border = component.getBorder();
    if (border instanceof TitledBorder) {
      final TitledBorder titledBorder = (TitledBorder)border;
      final String title = titledBorder.getTitle();
      if (title != null) {
        processUILabel(title, configurableOptions, path);
      }
    }
    if (component instanceof JLabel) {
      final String label = ((JLabel)component).getText();
      if (label != null) {
        processUILabel(label, configurableOptions, path);
      }
    }
    else if (component instanceof JCheckBox) {
      @NonNls final String checkBoxTitle = ((JCheckBox)component).getText();
      if (checkBoxTitle != null) {
        processUILabel(checkBoxTitle, configurableOptions, path);
      }
    }
    else if (component instanceof JRadioButton) {
      @NonNls final String radioButtonTitle = ((JRadioButton)component).getText();
      if (radioButtonTitle != null) {
        processUILabel(radioButtonTitle, configurableOptions, path);
      }
    }
    else if (component instanceof JButton) {
      @NonNls final String buttonTitle = ((JButton)component).getText();
      if (buttonTitle != null) {
        processUILabel(buttonTitle, configurableOptions, path);
      }
    }
    if (component instanceof JTabbedPane) {
      final JTabbedPane tabbedPane = (JTabbedPane)component;
      final int tabCount = tabbedPane.getTabCount();
      for (int i = 0; i < tabCount; i++) {
        final String title = path != null ? path + '.' + tabbedPane.getTitleAt(i) : tabbedPane.getTitleAt(i);
        processUILabel(title, configurableOptions, title);
        final Component tabComponent = tabbedPane.getComponentAt(i);
        if (tabComponent instanceof JComponent) {
          processComponent((JComponent)tabComponent, configurableOptions, title);
        }
      }
    }
    else {
      final Component[] components = component.getComponents();
      if (components != null) {
        for (Component child : components) {
          if (child instanceof JComponent) {
            processComponent((JComponent)child, configurableOptions, path);
          }
        }
      }
    }
  }

  private static void processUILabel(@NonNls final String title, final Set<OptionDescription> configurableOptions, String path) {
    final Set<String> words = SearchableOptionsRegistrar.getInstance().getProcessedWordsWithoutStemming(title);
    @NonNls final String regex = "[\\W&&[^\\p{Punct}\\p{Blank}]]";
    for (String option : words) {
      configurableOptions.add(new OptionDescription(option, HTML_PATTERN.matcher(title).replaceAll(" ").replaceAll(regex, " "), path));
    }
  }

  public static Runnable lightOptions(final SearchableConfigurable configurable,
                                      final JComponent component,
                                      final String option,
                                      final GlassPanel glassPanel) {
    return new Runnable() {
      public void run() {
        if (!SearchUtil.traverseComponentsTree(configurable, glassPanel, component, option, true)) {
          SearchUtil.traverseComponentsTree(configurable, glassPanel, component, option, false);
        }
      }
    };
  }

  public static int getSelection(String tabIdx, final JTabbedPane tabbedPane) {
    SearchableOptionsRegistrar searchableOptionsRegistrar = SearchableOptionsRegistrar.getInstance();
    for (int i = 0; i < tabbedPane.getTabCount(); i++) {
      final Set<String> pathWords = searchableOptionsRegistrar.getProcessedWords(tabIdx);
      final String title = tabbedPane.getTitleAt(i);
      final Set<String> titleWords = searchableOptionsRegistrar.getProcessedWords(title);
      pathWords.removeAll(titleWords);
      if (pathWords.isEmpty()) return i;
    }
    return -1;
  }

  public static int getSelection(String tabIdx, final TabbedPaneWrapper tabbedPane) {
    SearchableOptionsRegistrar searchableOptionsRegistrar = SearchableOptionsRegistrar.getInstance();
    for (int i = 0; i < tabbedPane.getTabCount(); i++) {
      final Set<String> pathWords = searchableOptionsRegistrar.getProcessedWords(tabIdx);
      final String title = tabbedPane.getTitleAt(i);
      final Set<String> titleWords = searchableOptionsRegistrar.getProcessedWords(title);
      pathWords.removeAll(titleWords);
      if (pathWords.isEmpty()) return i;
    }
    return -1;
  }

  private static boolean traverseComponentsTree(final SearchableConfigurable configurable,
                                                GlassPanel glassPanel,
                                                JComponent rootComponent,
                                                String option,
                                                boolean force) {
    if (option == null || option.trim().length() == 0) return false;
    boolean highlight = false;
    if (rootComponent instanceof JCheckBox) {
      final JCheckBox checkBox = ((JCheckBox)rootComponent);
      if (isComponentHighlighted(checkBox.getText(), option, force, configurable)) {
        highlight = true;
        glassPanel.addSpotlight(checkBox);
      }
    }
    else if (rootComponent instanceof JRadioButton) {
      final JRadioButton radioButton = ((JRadioButton)rootComponent);
      if (isComponentHighlighted(radioButton.getText(), option, force, configurable)) {
        highlight = true;
        glassPanel.addSpotlight(radioButton);
      }
    }
    else if (rootComponent instanceof JLabel) {
      final JLabel label = ((JLabel)rootComponent);
      if (isComponentHighlighted(label.getText(), option, force, configurable)) {
        highlight = true;
        glassPanel.addSpotlight(label);
      }
    }
    else if (rootComponent instanceof JButton) {
      final JButton button = ((JButton)rootComponent);
      if (isComponentHighlighted(button.getText(), option, force, configurable)) {
        highlight = true;
        glassPanel.addSpotlight(button);
      }
    }
    else if (rootComponent instanceof JTabbedPane) {
      final JTabbedPane tabbedPane = (JTabbedPane)rootComponent;
      final String path = SearchableOptionsRegistrarImpl.getInstance().getInnerPath(configurable, option);
      if (path != null) {
        final int index = SearchUtil.getSelection(path, tabbedPane);
        if (index > -1 && index < tabbedPane.getTabCount()) {
          tabbedPane.setSelectedIndex(index);
        }
      }
    }
    final Component[] components = rootComponent.getComponents();
    for (Component component : components) {
      if (component instanceof JComponent) {
        final boolean innerHighlight = traverseComponentsTree(configurable, glassPanel, (JComponent)component, option, force);
        if (innerHighlight) {
          highlight = true;
        }
      }
    }
    return highlight;
  }

  public static boolean isComponentHighlighted(String text, String option, final boolean force, final SearchableConfigurable configurable) {
    if (text == null || option == null) return false;
    SearchableOptionsRegistrar searchableOptionsRegistrar = SearchableOptionsRegistrar.getInstance();
    final Set<String> options =
      configurable != null ? searchableOptionsRegistrar.replaceSynonyms(searchableOptionsRegistrar.getProcessedWords(option), configurable) : null;
    if (options == null || options.isEmpty()) {
      return text.indexOf(option) != -1;
    }
    final Set<String> tokens = searchableOptionsRegistrar.getProcessedWords(text);
    if (!force) {
      options.retainAll(tokens);
      return !options.isEmpty();
    }
    else {
      options.removeAll(tokens);
      return options.isEmpty();
    }
  }

  public static Runnable lightOptions(final SearchableConfigurable configurable,
                                      final JComponent component,
                                      final String option,
                                      final GlassPanel glassPanel,
                                      final boolean forceSelect) {
    return new Runnable() {
      public void run() {
        SearchUtil.traverseComponentsTree(configurable, glassPanel, component, option, forceSelect);
      }
    };
  }

  public static String markup(@NonNls @NotNull String textToMarkup, String filter) {
    if (filter == null || filter.length() == 0) {
      return textToMarkup;
    }
    final Pattern insideHtmlTagPattern = Pattern.compile("[<[^<>]*>]*<[^<>]*");
    final SearchableOptionsRegistrar registrar = SearchableOptionsRegistrar.getInstance();
    final HashSet<String> quoted = new HashSet<String>();
    filter = processFilter(quoteStrictOccurences(textToMarkup, filter), quoted);
    final Set<String> options = registrar.getProcessedWords(filter);
    final Set<String> words = registrar.getProcessedWords(textToMarkup);
    for (String option : options) {
      if (words.contains(option)) {
        textToMarkup = markup(textToMarkup, insideHtmlTagPattern, option);
      }
    }
    for (String stripped : quoted) {
      textToMarkup = markup(textToMarkup, insideHtmlTagPattern, stripped);
    }
    return textToMarkup;
  }

  private static String quoteStrictOccurences(final String textToMarkup, final String filter) {
    String cur = "";
    for (String part : filter.split(" ")) {
      if (textToMarkup.toLowerCase().indexOf(part) != -1) {
        cur += "\"" + part + "\" ";
      } else {
        cur += part + " ";
      }
    }
    return cur;
  }

  private static String markup(@NonNls String textToMarkup, final Pattern insideHtmlTagPattern, final String option) {
    @NonNls String result = "";
    int beg = 0;
    int idx;
    while ((idx = StringUtil.indexOfIgnoreCase(textToMarkup, option, beg)) != -1) {
      final String prefix = textToMarkup.substring(beg, idx);
      final String toMark = textToMarkup.substring(idx, idx + option.length());
      if (insideHtmlTagPattern.matcher(prefix).matches()) {
        result += prefix + toMark;
      } else {
        result += prefix + "<font color='#ffffff' bgColor='#1d5da7'>" + toMark + "</font>";
      }
      beg = idx + option.length();
    }
    result += textToMarkup.substring(beg);
    return result;
  }

  public static void appendFragments(String filter,
                                     @NonNls String text,
                                     final int style,
                                     final Color foreground,
                                     final Color background,
                                     final ColoredTreeCellRenderer textRenderer) {
    if (text == null) return;
    if (filter == null || filter.length() == 0) {
      textRenderer.append(text, new SimpleTextAttributes(style, foreground));
    }
    else { //markup
      final HashSet<String> quoted = new HashSet<String>();
      filter = processFilter(quoteStrictOccurences(text, filter), quoted);
      final TreeMap<Integer, String> indx = new TreeMap<Integer, String>();
      for (String stripped : quoted) {
        int beg = 0;
        int idx;
        while ((idx = StringUtil.indexOfIgnoreCase(text, stripped, beg)) != -1) {
          indx.put(idx, text.substring(idx, idx + stripped.length()));
          beg = idx + stripped.length();
        }
      }

      final List<String> selectedWords = new ArrayList<String>();
      int pos = 0;
      for (Integer index : indx.keySet()) {
        final String stripped = indx.get(index);
        final int start = index.intValue();
        if (pos > start) {
          final String highlighted = selectedWords.get(selectedWords.size() - 1);
          if (highlighted.length() < stripped.length()){
            selectedWords.remove(highlighted);
          } else {
            continue;
          }
        }
        appendSelectedWords(text, selectedWords, pos, start, filter);
        selectedWords.add(stripped);
        pos = start + stripped.length();
      }
      appendSelectedWords(text, selectedWords, pos, text.length(), filter);

      int idx = 0;
      for (String word : selectedWords) {
        text = text.substring(idx);
        textRenderer.append(text.substring(0, text.indexOf(word)), new SimpleTextAttributes(background, foreground, null, style));
        idx = text.indexOf(word) + word.length();
        textRenderer.append(text.substring(idx - word.length(), idx), new SimpleTextAttributes(UIUtil.getTreeSelectionBackground(),
                                                                                               UIUtil.getTreeSelectionForeground(), null,
                                                                                               style));
      }
      textRenderer.append(text.substring(idx, text.length()), new SimpleTextAttributes(background, foreground, null, style));
    }
  }

  private static void appendSelectedWords(final String text, final List<String> selectedWords, final int pos, int end, final String filter) {
    if (pos < end) {
      final Set<String> filters = SearchableOptionsRegistrar.getInstance().getProcessedWords(filter);
      final String[] words = text.substring(pos, end).split("[\\W&&[^_-]]");
      for (String word : words) {
        if (filters.contains(PorterStemmerUtil.stem(word.toLowerCase()))) {
          selectedWords.add(word);
        }
      }
    }
  }

  @Nullable
  private static JBPopup createPopup(final ConfigurableSearchTextField searchField,
                                     final JBPopup[] activePopup,
                                     final Alarm showHintAlarm,
                                     final Consumer<String> selectConfigurable,
                                     final Project project,
                                     final int down) {

    final String filter = searchField.getText();
    if (filter == null || filter.length() == 0) return null;
    final Map<String, Set<String>> hints = SearchableOptionsRegistrar.getInstance().findPossibleExtension(filter, project);
    final DefaultListModel model = new DefaultListModel();
    final JList list = new JList(model);
    for (String groupName : hints.keySet()) {
      model.addElement(groupName);
      final Set<String> descriptions = hints.get(groupName);
      if (descriptions != null) {
        for (String hit : descriptions) {
          if (hit == null) continue;
          model.addElement(new OptionDescription(null, groupName, hit, null));
        }
      }
    }
    ListScrollingUtil.installActions(list);
    list.setCellRenderer(new DefaultListCellRenderer() {
      public Component getListCellRendererComponent(JList list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
        final Component rendererComponent = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
        if (value instanceof String) {
          setText("------ " + value + " ------");
        }
        else if (value instanceof OptionDescription) {
          setText(((OptionDescription)value).getHit());
        }
        return rendererComponent;
      }
    });


    if (model.size() > 0) {
      final Runnable onChosen = new Runnable() {
        public void run() {
          final Object selectedValue = list.getSelectedValue();
          if (selectedValue instanceof OptionDescription) {
            final OptionDescription description = ((OptionDescription)selectedValue);
            searchField.setText(description.getHit());
            searchField.addCurrentTextToHistory();
            SwingUtilities.invokeLater(new Runnable() {
              public void run() {     //do not show look up again
                showHintAlarm.cancelAllRequests();
                selectConfigurable.consume(description.getConfigurableId());
              }
            });
          }
        }
      };
      final JBPopup popup = JBPopupFactory.getInstance()
        .createListPopupBuilder(list)
        .setItemChoosenCallback(onChosen)
        .setRequestFocus(down != 0)
        .createPopup();
      list.addKeyListener(new KeyAdapter() {
        public void keyPressed(final KeyEvent e) {
          if (e.getKeyCode() != KeyEvent.VK_ENTER && e.getKeyCode() != KeyEvent.VK_UP && e.getKeyCode() != KeyEvent.VK_DOWN &&
              e.getKeyCode() != KeyEvent.VK_PAGE_UP && e.getKeyCode() != KeyEvent.VK_PAGE_DOWN) {
            searchField.requestFocusInWindow();
            if (cancelPopups(activePopup) && e.getKeyCode() == KeyEvent.VK_ESCAPE) {
              return;
            }
            if (e.getKeyChar() != KeyEvent.CHAR_UNDEFINED){
              searchField.process(new KeyEvent(searchField, KeyEvent.KEY_TYPED, e.getWhen(), e.getModifiers(), KeyEvent.VK_UNDEFINED, e.getKeyChar()));
            }
          }
        }

      });
      if (down > 0) {
        if (list.getSelectedIndex() < list.getModel().getSize() - 1) {
          list.setSelectedIndex(list.getSelectedIndex() + 1);
        }
      }
      else if (down < 0) {
        if (list.getSelectedIndex() > 0) {
          list.setSelectedIndex(list.getSelectedIndex() - 1);
        }
      }
      return popup;
    }
    return null;
  }

  public static void showHintPopup(final ConfigurableSearchTextField searchField,
                                   final JBPopup[] activePopup,
                                   final Alarm showHintAlarm,
                                   final Consumer<String> selectConfigurable,
                                   final Project project) {
    for (JBPopup aPopup : activePopup) {
      if (aPopup != null) {
        aPopup.cancel();
      }
    }

    final JBPopup popup = createPopup(searchField, activePopup, showHintAlarm, selectConfigurable, project, 0); //no selection
    if (popup != null) {
      popup.showUnderneathOf(searchField);
      searchField.requestFocusInWindow();
    }

    activePopup[0] = popup;
    activePopup[1] = null;
  }


  public static void registerKeyboardNavigation(final ConfigurableSearchTextField searchField,
                                                final JBPopup[] activePopup,
                                                final Alarm showHintAlarm,
                                                final Consumer<String> selectConfigurable,
                                                final Project project) {
    final Consumer<Integer> shower = new Consumer<Integer>() {
      public void consume(final Integer direction) {
        if (activePopup[0] != null) {
          activePopup[0].cancel();
        }

        if (activePopup[1] != null && activePopup[1].isVisible()) {
          return;
        }

        final JBPopup popup = createPopup(searchField, activePopup, showHintAlarm, selectConfigurable, project, direction.intValue());
        if (popup != null) {
          popup.showUnderneathOf(searchField);
        }
        activePopup[0] = null;
        activePopup[1] = popup;
      }
    };
    searchField.registerKeyboardAction(new ActionListener() {
      public void actionPerformed(ActionEvent e) {
        shower.consume(1);
      }
    }, KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
    searchField.registerKeyboardAction(new ActionListener() {
      public void actionPerformed(ActionEvent e) {
        shower.consume(-1);
      }
    }, KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);

    searchField.addKeyboardListener(new KeyAdapter() {
      public void keyPressed(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.VK_ESCAPE && searchField.getText().length() > 0) {
          e.consume();
          if (cancelPopups(activePopup)) return;
          searchField.setText("");
        }
        else if (e.getKeyCode() == KeyEvent.VK_ENTER) {
          searchField.addCurrentTextToHistory();
          cancelPopups(activePopup);
          if (e.getModifiers() == 0) {
            e.consume();
          }
        }
      }
    });
  }

  private static boolean cancelPopups(final JBPopup[] activePopup) {
    for (JBPopup popup : activePopup) {
      if (popup != null && popup.isVisible()) {
        popup.cancel();
        return true;
      }
    }
    return false;
  }

  public static List<Set<String>> findKeys(String filter, Set<String> quoted) {
    filter = processFilter(filter.toLowerCase(), quoted);
    final List<Set<String>> keySetList = new ArrayList<Set<String>>();
    final SearchableOptionsRegistrar optionsRegistrar = SearchableOptionsRegistrar.getInstance();
    final Set<String> words = optionsRegistrar.getProcessedWords(filter);
    for (String word : words) {
      final Set<OptionDescription> descriptions = ((SearchableOptionsRegistrarImpl)optionsRegistrar).getAcceptableDescriptions(word);
      Set<String> keySet = new HashSet<String>();
      if (descriptions != null) {
        for (OptionDescription description : descriptions) {
          keySet.add(description.getPath());
        }
      }
      keySetList.add(keySet);
    }
    return keySetList;
  }

  public static String processFilter(String filter, Set<String> quoted) {
    String withoutQuoted = "";
    int beg = 0;
    final Matcher matcher = QUOTED.matcher(filter);
    while (matcher.find()) {
      final int start = matcher.start(1);
      withoutQuoted += " " + filter.substring(beg, start);
      beg = matcher.end(1);
      final String trimmed = filter.substring(start, beg).trim();
      if (trimmed.length() > 0) {
        quoted.add(trimmed);
      }
    }
    return withoutQuoted + " " + filter.substring(beg);
  }

  //to process event
  public static class ConfigurableSearchTextField extends SearchTextFieldWithStoredHistory {
    public ConfigurableSearchTextField() {
      super("ALL_CONFIGURABLES_PANEL_SEARCH_HISTORY");
    }

    public void process(final KeyEvent e) {
      ((TextFieldWithProcessing)getTextEditor()).processKeyEvent(e);
    }
  }

  public static List<Configurable> expand(ConfigurableGroup[] groups) {
    final ArrayList<Configurable> result = new ArrayList<Configurable>();
    for (ConfigurableGroup eachGroup : groups) {
      result.addAll(expandGroup(eachGroup));
    }
    return result;
  }

  public static List<Configurable> expandGroup(final ConfigurableGroup group) {
    final Configurable[] configurables = group.getConfigurables();
    ArrayList<Configurable> result = new ArrayList<Configurable>();
    result.addAll(Arrays.asList(configurables));
    for (Configurable each : configurables) {
      addChildren(each, result);
    }
    return result;
  }

  private static void addChildren(Configurable configurable, ArrayList<Configurable> list) {
    if (configurable instanceof Configurable.Composite) {
      final Configurable[] kids = ((Configurable.Composite)configurable).getConfigurables();
      for (Configurable eachKid : kids) {
        list.add(eachKid);
        addChildren(eachKid, list);
      }
    }
  }

}
