/*
 * #%L
 * Script Editor and Interpreter for SciJava script languages.
 * %%
 * Copyright (C) 2009 - 2022 SciJava developers.
 * %%
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */

package org.scijava.ui.swing.script;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.Collection;
import java.util.List;

import javax.swing.Action;
import javax.swing.ImageIcon;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JViewport;
import javax.swing.KeyStroke;
import javax.swing.ToolTipManager;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.HyperlinkEvent;
import javax.swing.event.HyperlinkListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.Caret;
import javax.swing.text.DefaultEditorKit;
import javax.swing.text.Document;
import javax.swing.text.Element;
import javax.swing.text.Segment;

import org.fife.rsta.ac.LanguageSupport;
import org.fife.rsta.ac.LanguageSupportFactory;
import org.fife.ui.rsyntaxtextarea.RSyntaxDocument;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextAreaEditorKit;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextAreaEditorKit.CopyAsStyledTextAction;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextAreaEditorKit.ToggleCommentAction;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextAreaEditorKit.DecreaseIndentAction;
import org.fife.ui.rtextarea.RTextAreaEditorKit.*;
import org.fife.ui.rsyntaxtextarea.Style;
import org.fife.ui.rsyntaxtextarea.SyntaxScheme;
import org.fife.ui.rsyntaxtextarea.Theme;
import org.fife.ui.rtextarea.Gutter;
import org.fife.ui.rtextarea.GutterIconInfo;
import org.fife.ui.rtextarea.RTextArea;
import org.fife.ui.rtextarea.RTextAreaEditorKit;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.fife.ui.rtextarea.RecordableTextAction;
import org.fife.ui.rtextarea.SearchContext;
import org.fife.ui.rtextarea.SearchEngine;
import org.scijava.Context;
import org.scijava.log.LogService;
import org.scijava.platform.PlatformService;
import org.scijava.plugin.Parameter;
import org.scijava.prefs.PrefService;
import org.scijava.script.ScriptHeaderService;
import org.scijava.script.ScriptLanguage;
import org.scijava.script.ScriptService;
import org.scijava.util.FileUtils;

/**
 * Main text editing component of the script editor, based on
 * {@link RSyntaxTextArea}.
 *
 * @author Johannes Schindelin
 * @author Jonathan Hale
 */
public class EditorPane extends RSyntaxTextArea implements DocumentListener {

	private String fallBackBaseName;
	private File curFile;
	private File gitDirectory;
	private long fileLastModified;
	private ScriptLanguage currentLanguage;
	private Gutter gutter;
	private int modifyCount;

	private boolean undoInProgress;
	private boolean redoInProgress;
	private boolean autoCompletionEnabled;
	private boolean autoCompletionJavaFallback;
	private boolean autoCompletionWithoutKey;
	private String supportStatus;

	@Parameter
	Context context;
	@Parameter
	private LanguageSupportService languageSupportService;
	@Parameter
	private ScriptService scriptService;
	@Parameter
	private ScriptHeaderService scriptHeaderService;
	@Parameter
	private PrefService prefService;
	@Parameter
	private PlatformService platformService;
	@Parameter
	private LogService log;
	
	/**
	 * Constructor.
	 */
	public EditorPane() {

		// set sensible defaults
		setAntiAliasingEnabled(true);
		setAutoIndentEnabled(true);
		setBracketMatchingEnabled(true);
		setCloseCurlyBraces(true);
		setCloseMarkupTags(true);
		setCodeFoldingEnabled(true);
		setShowMatchedBracketPopup(true);
		setClearWhitespaceLinesEnabled(false); // most folks wont't want this set?
		// If a URL exists in commentaries this allows opening it using ctrl+click 
		setHyperlinksEnabled(true);
		addHyperlinkListener(new HyperlinkListener() {

			@Override
			public void hyperlinkUpdate(final HyperlinkEvent hle) {
				if (HyperlinkEvent.EventType.ACTIVATED.equals(hle.getEventType())) {
					try {
						platformService.open(hle.getURL());
					}
					catch (final IOException exc) {
						//ignored
					}
				}
			}
		});
		
		// load preferences
		loadPreferences();

		// Register recordable actions
		getActionMap().put(DefaultEditorKit.nextWordAction, wordMovement("Next-Word-Action", +1, false));
		getActionMap().put(DefaultEditorKit.selectionNextWordAction, wordMovement("Next-Word-Select-Action", +1, true));
		getActionMap().put(DefaultEditorKit.previousWordAction, wordMovement("Prev-Word-Action", -1, false));
		getActionMap().put(DefaultEditorKit.selectionPreviousWordAction,
				wordMovement("Prev-Word-Select-Action", -1, true));
		getActionMap().put(RTextAreaEditorKit.rtaTimeDateAction, new TimeDateAction());
		if (getActionMap().get(RTextAreaEditorKit.clipboardHistoryAction) != null)
			getActionMap().put(RTextAreaEditorKit.clipboardHistoryAction, new ClipboardHistoryAction());
		if (getActionMap().get(RSyntaxTextAreaEditorKit.rstaToggleCommentAction) != null)
			getActionMap().put(RSyntaxTextAreaEditorKit.rstaToggleCommentAction, new ToggleCommentAction());
		if (getActionMap().get(RSyntaxTextAreaEditorKit.rstaCopyAsStyledTextAction) != null)
			getActionMap().put(RSyntaxTextAreaEditorKit.rstaCopyAsStyledTextAction, new CopyAsStyledTextAction());

		adjustPopupMenu();

		ToolTipManager.sharedInstance().registerComponent(this);
		getDocument().addDocumentListener(this);
		addMouseListener(new MouseAdapter() {

			SearchContext context;

			@Override
			public void mousePressed(final MouseEvent me) {

				// 2022.02 TF: 'Mark All' occurrences is quite awkward. What is
				// marked is language-specific and the defaults are restricted
				// to certain identifiers. We'll hack things so that it works
				// for any selection by double-click. See
				// https://github.com/bobbylight/RSyntaxTextArea/issues/88
				if (getMarkOccurrences() && 2 == me.getClickCount()) {

					// Do nothing if getMarkOccurrences() is unset or no selection exists
					final String str = getSelectedText();
					if (str == null) return;

					if (context != null && str.equals(context.getSearchFor())) {
						// Selection is the previously 'marked all' scope. Clear it
						SearchEngine.markAll(EditorPane.this, new SearchContext());
						context = null;
					} else {
						// Use SearchEngine for 'mark all'
						final Color stashedColor = getMarkAllHighlightColor();
						setMarkAllHighlightColor(getMarkOccurrencesColor());
						context = new SearchContext(str, true);
						context.setMarkAll(true);
						context.setWholeWord(true);
						SearchEngine.markAll(EditorPane.this, context);
						setMarkAllHighlightColor(stashedColor);
					}
				}
			}
		});
	}

	private void adjustPopupMenu() {
		final JPopupMenu popup = super.getPopupMenu();
		JMenu menu = new JMenu("Move");
		popup.add(menu);
		menu.add(getMenuItem("Decrease Indent", new DecreaseIndentAction()));
		menu.add(getMenuItem("Increase Indent", new IncreaseIndentAction()));
		menu.addSeparator();
		menu.add(getMenuItem("Move Up", new LineMoveAction(RTextAreaEditorKit.rtaLineUpAction, -1)));
		menu.add(getMenuItem("Move Down", new LineMoveAction(RTextAreaEditorKit.rtaLineDownAction, 1)));
		menu = new JMenu("Transform");
		popup.add(menu);
		menu.add(getMenuItem("Camel Case", new CamelCaseAction()));
		menu.add(getMenuItem("Invert Case", new InvertSelectionCaseAction()));
		menu.add(getMenuItem("Lower Case", new LowerSelectionCaseAction()));
		menu.add(getMenuItem("Upper Case", new UpperSelectionCaseAction()));
	}

	private JMenuItem getMenuItem(final String label, final RecordableTextAction a) {
		JMenuItem item = new JMenuItem(a);
		item.setAccelerator((KeyStroke) a.getValue(Action.ACCELERATOR_KEY));
		if (getActionMap().get(a.getName()) == null)
			getActionMap().put(a.getName(), a); // make it recordable
		item.setText(label);
		return item;
	}

	@Override
	public void setTabSize(final int width) {
		if (getTabSize() != width) super.setTabSize(width);
	}

	/**
	 * Add this {@link EditorPane} with scrollbars to a container.
	 *
	 * @param container the container to add this editor pane to.
	 */
	public void embedWithScrollbars(final Container container) {
		container.add(wrappedInScrollbars());
	}

	/**
	 * @return this EditorPane wrapped in a {@link RTextScrollPane}.
	 */
	public RTextScrollPane wrappedInScrollbars() {
		final RTextScrollPane sp = new RTextScrollPane(this);
		sp.setPreferredSize(new Dimension(600, 350));
		sp.setIconRowHeaderEnabled(true);
		gutter = sp.getGutter();
		gutter.setBookmarkingEnabled(true);
		updateBookmarkIcon();
		gutter.setShowCollapsedRegionToolTips(true);
		gutter.setFoldIndicatorEnabled(true);
		return sp;
	}

	protected void updateBookmarkIcon() {
		// this will clear existing bookmarks, so we'll need restore existing ones
		final GutterIconInfo[] stash = gutter.getBookmarks();
		gutter.setBookmarkIcon(createBookmarkIcon());
		try {
			for (final GutterIconInfo info : stash)
				gutter.toggleBookmark(info.getMarkedOffset());
		} catch (final BadLocationException ignored) {
			JOptionPane.showMessageDialog(this, "Some bookmarks may have been lost.", "Lost Bookmarks",
					JOptionPane.WARNING_MESSAGE);
		}
	}

	private ImageIcon createBookmarkIcon() {
		final int size = gutter.getLineNumberFont().getSize();
		final BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		final Graphics2D graphics = image.createGraphics();
		graphics.setColor(gutter.getLineNumberColor());
		graphics.fillRect(0, 0, size, size);
		graphics.setXORMode(getCurrentLineHighlightColor());
		graphics.drawRect(0, 0, size - 1, size - 1);
		image.flush();
		return new ImageIcon(image);
	}

	RecordableTextAction wordMovement(final String id, final int direction, final boolean select) {
		return new RecordableTextAction(id) {
			private static final long serialVersionUID = 1L;

			@Override
			public String getDescription() {
				final StringBuilder sb = new StringBuilder();
				if (direction > 0)
					sb.append("Next");
				else
					sb.append("Previous");
				sb.append("Word");
				if (select) sb.append("Select");
				return sb.toString();
			}

			@Override
			public void actionPerformedImpl(final ActionEvent e,
				final RTextArea textArea)
			{
				int pos = textArea.getCaretPosition();
				final int end = direction < 0 ? 0 : textArea.getDocument().getLength();
				while (pos != end && !isWordChar(textArea, pos))
					pos += direction;
				while (pos != end && isWordChar(textArea, pos))
					pos += direction;
				if (select) textArea.moveCaretPosition(pos);
				else textArea.setCaretPosition(pos);
			}

			@Override
			public String getMacroID() {
				return id;
			}

			boolean isWordChar(final RTextArea textArea, final int pos) {
				try {
					final char c =
						textArea.getText(pos + (direction < 0 ? -1 : 0), 1).charAt(0);
					return c > 0x7f || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') ||
						(c >= '0' && c <= '9') || c == '_';
				}
				catch (final BadLocationException e) {
					return false;
				}
			}
		};
	}

	@Override
	public void undoLastAction() {
		undoInProgress = true;
		super.undoLastAction();
		undoInProgress = false;
	}

	@Override
	public void redoLastAction() {
		redoInProgress = true;
		super.redoLastAction();
		redoInProgress = false;
	}

	/**
	 * @return <code>true</code> if the file in this {@link EditorPane} was
	 *         changes since it was last saved.
	 */
	public boolean fileChanged() {
		return modifyCount != 0;
	}

	@Override
	public void insertUpdate(final DocumentEvent e) {
		modified();
	}

	@Override
	public void removeUpdate(final DocumentEvent e) {
		modified();
	}

	// triggered only by syntax highlighting
	@Override
	public void changedUpdate(final DocumentEvent e) {}

	/**
	 * Set the title according to whether the file was modified or not.
	 */
	protected void modified() {
		if (undoInProgress) {
			modifyCount--;
		}
		else if (redoInProgress || modifyCount >= 0) {
			modifyCount++;
		}
		else {
			// not possible to get back to clean state
			modifyCount = Integer.MIN_VALUE;
		}
	}

	/**
	 * @return <code>true</code> if the file in this {@link EditorPane} is an
	 *         unsaved new file which has not been edited yet.
	 */
	public boolean isNew() {
		return !fileChanged() && curFile == null && fallBackBaseName == null &&
			getDocument().getLength() == 0;
	}

	/**
	 * @return true if the file in this {@link EditorPane} was changed ouside of
	 *         this {@link EditorPane} since it was openend.
	 */
	public boolean wasChangedOutside() {
		return curFile != null && curFile.exists() &&
			curFile.lastModified() != fileLastModified;
	}

	/**
	 * Write the contents of this {@link EditorPane} to given file.
	 *
	 * @param file File to write the contents of this editor to.
	 * @throws IOException Thrown when a parent directory could not be created.
	 */
	public void write(final File file) throws IOException {
		final File dir = file.getParentFile();
		if (dir != null && !dir.exists()) {
			// create needed parent directories
			if (!dir.mkdirs()) {
				throw new IOException("Cannot create directory: " + dir);
			}
		}
		final BufferedWriter outFile =
			new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file),
				"UTF-8"));
		outFile.write(getText());
		outFile.close();
		modifyCount = 0;
		fileLastModified = file.lastModified();
	}

	/**
	 * Load editor contents from given file.
	 *
	 * @param file file to load.
	 * @throws IOException Thrown if the canonical file path couldn't be obtained for the file. 
	 */
	public void open(final File file) throws IOException {
		final File oldFile = curFile;
		curFile = null;
		if (file == null) setText("");
		else {
			int line = 0;
			try {
				if (file.getCanonicalPath().equals(oldFile.getCanonicalPath())) line =
					getCaretLineNumber();
			}
			catch (final Exception e) { /* ignore */}
			if (!file.exists()) {
				modifyCount = Integer.MIN_VALUE;
				setFileName(file);
				return;
			}
			final StringBuffer string = new StringBuffer();
			final BufferedReader reader =
				new BufferedReader(new InputStreamReader(new FileInputStream(file),
					"UTF-8"));
			final char[] buffer = new char[16384];
			for (;;) {
				final int count = reader.read(buffer);
				if (count < 0) break;
				string.append(buffer, 0, count);
			}
			reader.close();
			setText(string.toString());
			curFile = file;
			if (line > getLineCount()) line = getLineCount() - 1;
			try {
				setCaretPosition(getLineStartOffset(line));
			}
			catch (final BadLocationException e) { /* ignore */}
		}
		discardAllEdits();
		modifyCount = 0;
		fileLastModified = file == null || !file.exists() ? 0 : file.lastModified();
	}

	/**
	 * Set the name to use for new files. The file extension for the current
	 * script language is added automatically.
	 *
	 * @param baseName the fallback base name.
	 */
	public void setFileName(final String baseName) {
		fallBackBaseName = baseName;
		if (currentLanguage == null) {
			return;
		}
		for (String extension : currentLanguage.getExtensions()) {
			extension = "." + extension;
			if (baseName.endsWith(extension)) {
				fallBackBaseName =
					fallBackBaseName.substring(0, fallBackBaseName.length() -
						extension.length());
				break;
			}
		}

		if (currentLanguage.getLanguageName().equals("Java")) {
			new TokenFunctions(this).setClassName(fallBackBaseName);
		}
	}

	/**
	 * TODO
	 *
	 * @param file The file to edit in this {@link EditorPane}.
	 */
	public void setFileName(final File file) {
		curFile = file;

		if (file != null) {
			setLanguageByFileName(file.getName());
			fallBackBaseName = null;
		}
		fileLastModified = file == null || !file.exists() ? 0 : file.lastModified();
	}

	/**
	 * Get the directory of the git repository for the currently open file.
	 *
	 * @return the git repository directoy, or <code>null</code> is there is no
	 *         such thing.
	 */
	public File getGitDirectory() {
		return gitDirectory;
	}

	/**
	 * Set this {@link EditorPane}s git directory.
	 *
	 * @param dir directory to set the git directory to.
	 */
	public void setGitDirectory(final File dir) {
		gitDirectory = dir;
	}

	/**
	 * @return name of the currently open file.
	 */
	protected String getFileName() {
		if (curFile != null) return curFile.getName();
		String extension = "";
		if (currentLanguage != null) {
			final List<String> extensions = currentLanguage.getExtensions();
			if (extensions.size() > 0) {
				extension = "." + extensions.get(0);
			}
			if (currentLanguage.getLanguageName().equals("Java")) {
				final String name = new TokenFunctions(this).getClassName();
				if (name != null) {
					return name + extension;
				}
			}
		}
		return (fallBackBaseName == null ? "New_" : fallBackBaseName) + extension;
	}

	/**
	 * Get the language by filename extension.
	 *
	 * @param name the filename.
	 * @see #setLanguage(ScriptLanguage)
	 * @see #setLanguage(ScriptLanguage, boolean)
	 */
	protected void setLanguageByFileName(final String name) {
		setLanguage(scriptService.getLanguageByExtension(FileUtils
			.getExtension(name)));
	}

	/**
	 * Set the language of this {@link EditorPane}.
	 *
	 * @param language {@link ScriptLanguage} to set the editors language to.
	 * @see #setLanguageByFileName(String)
	 * @see #setLanguage(ScriptLanguage, boolean)
	 */
	protected void setLanguage(final ScriptLanguage language) {
		setLanguage(language, false);
	}

	/**
	 * Set the language of this {@link EditorPane}, optionally adding a header.
	 * TODO: What is this header?
	 *
	 * @param language {@link ScriptLanguage} to set the editors language to.
	 * @param addHeader set to <code>true</code> to add a header.
	 * @see #setLanguageByFileName(String)
	 * @see #setLanguage(ScriptLanguage)
	 */
	protected void setLanguage(final ScriptLanguage language,
		final boolean addHeader)
	{
		// uninstall existing language support.
		LanguageSupport support =
			languageSupportService.getLanguageSupport(currentLanguage);
		if (support != null) {
			support.uninstall(this);
		}

		String languageName;
		String defaultExtension;

		if (language == null) {
			languageName = "None";
			defaultExtension = ".txt";
		}
		else {
			languageName = language.getLanguageName();
			final List<String> extensions = language.getExtensions();
			defaultExtension =
				extensions.size() == 0 ? "" : ("." + extensions.get(0));
		}
		if (fallBackBaseName != null && fallBackBaseName.endsWith(".txt")) fallBackBaseName =
			fallBackBaseName.substring(0, fallBackBaseName.length() - 4);
		if (curFile != null) {
			String name = curFile.getName();
			final String ext = "." + FileUtils.getExtension(name);
			if (!defaultExtension.equals(ext)) {
				name = name.substring(0, name.length() - ext.length());
				curFile = new File(curFile.getParentFile(), name + defaultExtension);
				modifyCount = Integer.MIN_VALUE;
			}
		}
		String header = null;

		if (addHeader && currentLanguage == null) {
			header = scriptHeaderService.getHeader(language);
		}
		currentLanguage = language;

		final String styleName =
			"text/" + languageName.toLowerCase().replace(' ', '-');
		try {
			setSyntaxEditingStyle(styleName);
		}
		catch (final NullPointerException exc) {
			// NB: Avoid possible NPEs in RSyntaxTextArea code.
			// See: https://fiji.sc/bug/1181.html
			log.debug(exc);
		}

		// Add header text
		if (header != null) {
			setText(header += getText());
		}

		if ("None".equals(languageName) ) {
			supportStatus = null; // no need to update console
			return;
		}
		String supportLevel = "SciJava supported";
		// try to get language support for current language, may be null.
		support = languageSupportService.getLanguageSupport(currentLanguage);

		// that did not work. See if there is internal support for it.
		if (support == null) {
			support = LanguageSupportFactory.get().getSupportFor(styleName);
			supportLevel = "Legacy supported";
		}
		// that did not work, Fallback to Java
		if (support == null && autoCompletionJavaFallback) {
			support = languageSupportService.getLanguageSupport(scriptService.getLanguageByName("Java"));
			supportLevel = "N/A. Using Java as fallback";
		}
		if (support != null) {
			support.setAutoCompleteEnabled(autoCompletionEnabled);
			support.setAutoActivationEnabled(autoCompletionWithoutKey);
			support.install(this);
			if (!autoCompletionEnabled)
				supportLevel += " but currently disabled\n";
			else {
				supportLevel += " triggered by Ctrl+Space";
				if (autoCompletionWithoutKey)
					supportLevel += " & auto-display ";
				supportLevel += "\n";
			}
		} else {
			supportLevel = "N/A";
		}
		supportStatus = "Active language: " + languageName + "\nAutocompletion: " + supportLevel;
	}

	/**
	 * Toggles whether auto-completion is enabled.
	 * 
	 * @param enabled Whether auto-activation is enabled.
	 */
	public void setAutoCompletion(final boolean enabled) {
		autoCompletionEnabled = enabled;
		if (currentLanguage != null)
			setLanguage(currentLanguage);
	}

	/**
	 * Toggles whether auto-completion should adopt Java completions if the current
	 * language does not support auto-completion.
	 * 
	 * @param enabled Whether Java should be enabled as fallback language for
	 *                auto-completion
	 */
	void setFallbackAutoCompletion(final boolean value) {
		autoCompletionJavaFallback = value;
		if (autoCompletionEnabled && currentLanguage != null)
			setLanguage(currentLanguage);
	}

	/**
	 * Toggles whether auto-activation of auto-completion is enabled. Ignored if
	 * auto-completion is not enabled.
	 *
	 * @param enabled Whether auto-activation is enabled.
	 */
	void setKeylessAutoCompletion(final boolean enabled) {
		autoCompletionWithoutKey = enabled;
		if (autoCompletionEnabled && currentLanguage != null)
			setLanguage(currentLanguage);
	}

	public boolean isAutoCompletionEnabled() {
		return autoCompletionEnabled;
	}

	public boolean isAutoCompletionKeyless() {
		return autoCompletionWithoutKey;
	}

	public boolean isAutoCompletionFallbackEnabled() {
		return autoCompletionJavaFallback;
	}

	/**
	 * Get file currently open in this {@link EditorPane}.
	 *
	 * @return the file.
	 */
	public File getFile() {
		return curFile;
	}

	/**
	 * Get {@link ScriptLanguage} used for this {@link EditorPane}.
	 *
	 * @return current {@link ScriptLanguage}.
	 */
	public ScriptLanguage getCurrentLanguage() {
		return currentLanguage;
	}

	/**
	 * @return font size of this editor.
	 */
	public float getFontSize() {
		return getFont().getSize2D();
	}

	/**
	 * Set the font size for this editor.
	 *
	 * @param size the new font size.
	 */
	public void setFontSize(final float size) {
		increaseFontSize(size / getFontSize());
	}

	/**
	 * Increase font size of this editor by a given factor.
	 *
	 * @param factor Factor to increase font size.
	 */
	public void increaseFontSize(final float factor) {
		if (factor == 1) return;
		final SyntaxScheme scheme = getSyntaxScheme();
		for (int i = 0; i < scheme.getStyleCount(); i++) {
			final Style style = scheme.getStyle(i);
			if (style == null || style.font == null) continue;
			final float size = Math.max(5, style.font.getSize2D() * factor);
			style.font = style.font.deriveFont(size);
		}
		final Font font = getFont();
		final float size = Math.max(5, font.getSize2D() * factor);
		setFont(font.deriveFont(size));
		setSyntaxScheme(scheme);
		// Adjust gutter size
		if (gutter != null) {
			final float lnSize = size * 0.8f;
			gutter.setLineNumberFont(font.deriveFont(lnSize));
			updateBookmarkIcon();
		}
		Component parent = getParent();
		if (parent instanceof JViewport) {
			parent = parent.getParent();
			if (parent instanceof JScrollPane) {
				parent.repaint();
			}
		}
		parent.repaint();
	}

	/**
	 * @return the underlying {@link RSyntaxDocument}.
	 */
	protected RSyntaxDocument getRSyntaxDocument() {
		return (RSyntaxDocument) getDocument();
	}

	/**
	 * Add/remove bookmark for line containing the cursor/caret.
	 */
	public void toggleBookmark() {
		toggleBookmark(getCaretLineNumber());
	}

	/**
	 * Add/remove bookmark for a specific line.
	 *
	 * @param line line to toggle the bookmark on.
	 */
	public void toggleBookmark(final int line) {
		if (gutter != null) {
			try {
				gutter.toggleBookmark(line);
			}
			catch (final BadLocationException e) {
				/* ignore */
				JOptionPane.showMessageDialog(this, "Cannot toggle bookmark at this location.", "Error",
						JOptionPane.ERROR_MESSAGE);
			}
		}
	}

	/**
	 * Add this editors bookmarks to the specified collection.
	 *
	 * @param tab Tab index to set for added bookmarks.
	 * @param result Collection to add the bookmarks to.
	 */
	public void getBookmarks(final TextEditorTab tab,
		final Collection<Bookmark> result)
	{
		if (gutter == null) return;

		for (final GutterIconInfo info : gutter.getBookmarks())
			result.add(new Bookmark(tab, info));
	}

	/**
	 * Adapted from ij.plugin.frame.Editor. Replaces unquoted invalid ascii
	 * characters with spaces. Characters are considered invalid if outside the
	 * range of [32, 127] (except newlines and vertical tabs).
	 *
	 * @return number of characters replaced.
	 */
	public int zapGremlins() {
		final char[] chars = getText().toCharArray();
		int count = 0; // number of "gremlins" zapped
		boolean inQuotes = false;
		char quoteChar = 0;

		for (int i = 0; i < chars.length; ++i) {
			final char c = chars[i];

			if (!inQuotes) {
				if (c == '"' || c == '\'') {
					inQuotes = true;
					quoteChar = c;
				}
				else if (c != '\n' && c != '\t' && (c < 32 || c > 127)) {
					count++;
					chars[i] = ' ';
				}
			}
			else if (c == quoteChar || c == '\n') {
				inQuotes = false;
			}
		}
		if (count > 0) {
			beginAtomicEdit();
			try {
				setText(new String(chars));
			}
			catch (final Throwable t) {
				log.error(t);
			}
			finally {
				endAtomicEdit();
			}
		}
		return count;
	}

	@Override
	public void convertTabsToSpaces() {
		beginAtomicEdit();
		try {
			super.convertTabsToSpaces();
		}
		catch (final Throwable t) {
			log.error(t);
		}
		finally {
			endAtomicEdit();
		}
	}

	@Override
	public void convertSpacesToTabs() {
		beginAtomicEdit();
		try {
			super.convertSpacesToTabs();
		}
		catch (final Throwable t) {
			log.error(t);
		}
		finally {
			endAtomicEdit();
		}
	}

	// --- Preferences ---
	public static final String FONT_SIZE_PREFS = "script.editor.FontSize";
	public static final String LINE_WRAP_PREFS = "script.editor.WrapLines";
	public static final String TAB_SIZE_PREFS = "script.editor.TabSize";
	public static final String TABS_EMULATED_PREFS = "script.editor.TabsEmulated";
	public static final String WHITESPACE_VISIBLE_PREFS = "script.editor.Whitespace";
	public static final String TABLINES_VISIBLE_PREFS = "script.editor.Tablines";
	public static final String MARGIN_VISIBLE_PREFS = "script.editor.Margin";
	public static final String THEME_PREFS = "script.editor.theme";
	public static final String AUTOCOMPLETE_PREFS = "script.editor.AC";
	public static final String AUTOCOMPLETE_KEYLESS_PREFS = "script.editor.ACNoKey";
	public static final String AUTOCOMPLETE_FALLBACK_PREFS = "script.editor.ACFallback";
	public static final String MARK_OCCURRENCES_PREFS = "script.editor.Occurrences";
	public static final String FOLDERS_PREFS = "script.editor.folders";
	public static final int DEFAULT_TAB_SIZE = 4;
	public static final String DEFAULT_THEME = "default";

	/**
	 * Loads and applies the preferences for the tab
	 */
	public void loadPreferences() {
		if (prefService == null) {
			setLineWrap(false);
			setTabSize(DEFAULT_TAB_SIZE);
			setLineWrap(false);
			setTabsEmulated(false);
			setPaintTabLines(false);
			setAutoCompletion(true);
			setKeylessAutoCompletion(true); // true for backwards compatibility with IJ1 macro auto-completion
			setFallbackAutoCompletion(false);
			setMarkOccurrences(false);
		} else {
			resetTabSize();
			setFontSize(prefService.getFloat(getClass(), FONT_SIZE_PREFS, getFontSize()));
			setLineWrap(prefService.getBoolean(getClass(), LINE_WRAP_PREFS, getLineWrap()));
			setTabsEmulated(prefService.getBoolean(getClass(), TABS_EMULATED_PREFS, getTabsEmulated()));
			setWhitespaceVisible(prefService.getBoolean(getClass(), WHITESPACE_VISIBLE_PREFS, isWhitespaceVisible()));
			setPaintTabLines(prefService.getBoolean(getClass(), TABLINES_VISIBLE_PREFS, getPaintTabLines()));
			setAutoCompletion(prefService.getBoolean(getClass(), AUTOCOMPLETE_PREFS, true));
			setKeylessAutoCompletion(prefService.getBoolean(getClass(), AUTOCOMPLETE_KEYLESS_PREFS, true)); // true for backwards compatibility with IJ1 macro
			setFallbackAutoCompletion(prefService.getBoolean(getClass(), AUTOCOMPLETE_FALLBACK_PREFS, false));
			setMarkOccurrences(prefService.getBoolean(getClass(), MARK_OCCURRENCES_PREFS, false));
			setMarginLineEnabled(prefService.getBoolean(getClass(), MARGIN_VISIBLE_PREFS, false));
			applyTheme(themeName());
		}
	}

	/**
	 * Applies a theme to this pane.
	 *
	 * @param theme either "default", "dark", "druid", "eclipse", "idea", "monokai",
	 *              "vs"
	 * @throws IllegalArgumentException If {@code theme} is not a valid option, or
	 *                                  the resource could not be loaded
	 */
	public void applyTheme(final String theme) throws IllegalArgumentException {
		try {
			applyTheme(getTheme(theme));
		} catch (final Exception ex) {
			throw new IllegalArgumentException(ex);
		}
	}

	public String themeName() {
		return prefService.get(getClass(), THEME_PREFS, DEFAULT_THEME);
	}

	private void applyTheme(final Theme th) throws IllegalArgumentException {
		// themes include font size, so we'll need to reset that
		final float existingFontSize = getFontSize();
		th.apply(this);
		setFontSize(existingFontSize);
		updateBookmarkIcon(); // update bookmark icon color
	}

	private static Theme getTheme(final String theme) throws IllegalArgumentException {
		try {
			return Theme
					.load(TextEditor.class.getResourceAsStream("/org/fife/ui/rsyntaxtextarea/themes/" + theme + ".xml"));
		} catch (final Exception ex) {
			throw new IllegalArgumentException(ex);
		}
	}

	public String loadFolders() {
		return prefService.get(getClass(), FOLDERS_PREFS, System.getProperty("user.home"));
	}

	/**
	 * Retrieves and saves the preferences to the persistent store.
	 *
	 * @param top_folders the File Explorer's pane top folder paths (":" separated list)
	 * @param theme the Script Editor's theme
	 */
	public void savePreferences(final String top_folders, final String theme) {
		prefService.put(getClass(), TAB_SIZE_PREFS, getTabSize());
		prefService.put(getClass(), FONT_SIZE_PREFS, getFontSize());
		prefService.put(getClass(), LINE_WRAP_PREFS, getLineWrap());
		prefService.put(getClass(), TABS_EMULATED_PREFS, getTabsEmulated());
		prefService.put(getClass(), WHITESPACE_VISIBLE_PREFS, isWhitespaceVisible());
		prefService.put(getClass(), TABLINES_VISIBLE_PREFS, getPaintTabLines());
		prefService.put(getClass(), AUTOCOMPLETE_PREFS, isAutoCompletionEnabled());
		prefService.put(getClass(), AUTOCOMPLETE_KEYLESS_PREFS, isAutoCompletionKeyless());
		prefService.put(getClass(), AUTOCOMPLETE_FALLBACK_PREFS, isAutoCompletionFallbackEnabled());
		prefService.put(getClass(), MARGIN_VISIBLE_PREFS, isMarginLineEnabled());
		prefService.put(getClass(), MARK_OCCURRENCES_PREFS, getMarkOccurrences());
		if (null != top_folders) prefService.put(getClass(), FOLDERS_PREFS, top_folders);
		if (null != theme) prefService.put(getClass(), THEME_PREFS, theme);
	}

	/**
	 * Reset tab size to current preferences.
	 */
	public void resetTabSize() {
		setTabSize(prefService.getInt(getClass(), TAB_SIZE_PREFS, DEFAULT_TAB_SIZE));
	}

	String getSupportStatus() {
		return supportStatus;
	}

	static class CamelCaseAction extends RecordableTextAction {
		private static final long serialVersionUID = 1L;

		CamelCaseAction() {
			super("RTA.CamelCaseAction");
		}

		@Override
		public void actionPerformedImpl(final ActionEvent e, final RTextArea textArea) {
			if (!textArea.isEditable() || !textArea.isEnabled()) {
				UIManager.getLookAndFeel().provideErrorFeedback(textArea);
				return;
			}
			final String selection = textArea.getSelectedText();
			if (selection != null) {
				final String[] words = selection.split("[\\W_]+");
				final StringBuilder buffer = new StringBuilder();
				for (int i = 0; i < words.length; i++) {
					String word = words[i];
					if (i == 0) {
						word = word.isEmpty() ? word : word.toLowerCase();
					} else {
						word = word.isEmpty() ? word
								: Character.toUpperCase(word.charAt(0)) + word.substring(1).toLowerCase();
					}
					buffer.append(word);
				}
				textArea.replaceSelection(buffer.toString());
			}
			textArea.requestFocusInWindow();
		}

		@Override
		public String getMacroID() {
			return getName();
		}

	}

	/** Modified from DecreaseIndentAction */
	static class IncreaseIndentAction extends RecordableTextAction {

		private static final long serialVersionUID = 1L;

		private final Segment s;

		public IncreaseIndentAction() {
			super("RSTA.IncreaseIndentAction");
			s = new Segment();
		}

		@Override
		public void actionPerformedImpl(final ActionEvent e, final RTextArea textArea) {

			if (!textArea.isEditable() || !textArea.isEnabled()) {
				UIManager.getLookAndFeel().provideErrorFeedback(textArea);
				return;
			}

			final Document document = textArea.getDocument();
			final Element map = document.getDefaultRootElement();
			final Caret c = textArea.getCaret();
			int dot = c.getDot();
			int mark = c.getMark();
			int line1 = map.getElementIndex(dot);
			final int tabSize = textArea.getTabSize();
			final StringBuilder sb = new StringBuilder();
			if (textArea.getTabsEmulated()) {
				while (sb.length() < tabSize) {
					sb.append(' ');
				}
			} else {
				sb.append('\t');
			}
			final String paddingString = sb.toString();

			// If there is a selection, indent all lines in the selection.
			// Otherwise, indent the line the caret is on.
			if (dot != mark) {
				final int line2 = map.getElementIndex(mark);
				dot = Math.min(line1, line2);
				mark = Math.max(line1, line2);
				Element elem;
				textArea.beginAtomicEdit();
				try {
					for (line1 = dot; line1 < mark; line1++) {
						elem = map.getElement(line1);
						handleIncreaseIndent(elem, document, paddingString);
					}
					// Don't do the last line if the caret is at its
					// beginning. We must call getDot() again and not just
					// use 'dot' as the caret's position may have changed
					// due to the insertion of the tabs above.
					elem = map.getElement(mark);
					final int start = elem.getStartOffset();
					if (Math.max(c.getDot(), c.getMark()) != start) {
						handleIncreaseIndent(elem, document, paddingString);
					}
				} catch (final BadLocationException ble) {
					ble.printStackTrace();
					UIManager.getLookAndFeel().provideErrorFeedback(textArea);
				} finally {
					textArea.endAtomicEdit();
				}
			} else {
				final Element elem = map.getElement(line1);
				try {
					handleIncreaseIndent(elem, document, paddingString);
				} catch (final BadLocationException ble) {
					ble.printStackTrace();
					UIManager.getLookAndFeel().provideErrorFeedback(textArea);
				}
			}

		}

		@Override
		public final String getMacroID() {
			return getName();
		}

		private void handleIncreaseIndent(final Element elem, final Document doc, final String pad)
				throws BadLocationException {
			final int start = elem.getStartOffset();
			int end = elem.getEndOffset() - 1; // Why always true??
			doc.getText(start, end - start, s);
			final int i = s.offset;
			end = i + s.count;
			if (end > i || (end == i && i == 0)) {
				doc.insertString(start, pad, null);
			}
		}

	}

}
