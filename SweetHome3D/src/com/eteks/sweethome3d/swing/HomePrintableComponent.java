/*
 * HomePrintableComponent.java 27 aout 07
 *
 * Sweet Home 3D, Copyright (c) 2007 Emmanuel PUYBARET / eTeks <info@eteks.com>
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */
package com.eteks.sweethome3d.swing;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.print.PageFormat;
import java.awt.print.Paper;
import java.awt.print.Printable;
import java.awt.print.PrinterException;
import java.awt.print.PrinterJob;
import java.security.AccessControlException;
import java.text.MessageFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.MissingResourceException;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

import javax.swing.JComponent;
import javax.swing.JLabel;

import com.eteks.sweethome3d.model.Home;
import com.eteks.sweethome3d.model.HomePrint;
import com.eteks.sweethome3d.model.LengthUnit;
import com.eteks.sweethome3d.model.Level;
import com.eteks.sweethome3d.viewcontroller.ContentManager;
import com.eteks.sweethome3d.viewcontroller.HomeController;
import com.eteks.sweethome3d.viewcontroller.PlanView;

/**
 * A printable component used to print or preview the furniture, the plan 
 * and the 3D view of a home.
 */
public class HomePrintableComponent extends JComponent implements Printable {
  /**
   * List of the variables that the user may insert in header and footer.
   */
  public enum Variable {
    PAGE_NUMBER("$pageNumber", "{0, number, integer}"),
    PAGE_COUNT("$pageCount", "{1, number, integer}"),
    PLAN_SCALE("$planScale", "{2}"),
    DATE("$date", "{3, date}"),
    TIME("$time", "{3, time}"),
    HOME_PRESENTATION_NAME("$name", "{4}"),
    HOME_NAME("$file", "{5}"),
    LEVEL_NAME("$level", "{6}");    
    
    private final String userCode;
    private final String formatCode;

    private Variable(String userCode, String formatCode) {
      this.userCode = userCode;
      this.formatCode = formatCode;      
    }
    
    /**
     * Returns a user readable code matching this field.
     */
    public String getUserCode() {
      return this.userCode;
    }
    
    /**
     * Returns a format usable code matching this field.
     */
    public String getFormatCode()  {
      return this.formatCode;
    }
    
    /**
     * Returns the message format built from a format that uses variables.
     */
    public static MessageFormat getMessageFormat(String format) {
      // Replace $$ escape sequence ($$ is the escape sequence for $ character)
      final String temp = "|#&%<>/!";
      format = format.replace("$$", temp);
      // Replace MessageFormat escape sequences
      format = format.replace("'", "''");
      format = format.replace("{", "'{'");
      // Replace variable by their MessageFormat code
      for (Variable variable : Variable.values()) {
        format = format.replace(variable.getUserCode(), variable.getFormatCode());
      }
      format = format.replace(temp, "$");
      return new MessageFormat(format);
    }
  };

  private static final float   HEADER_FOOTER_MARGIN = LengthUnit.centimeterToInch(0.2f) * 72;
  
  private final Home           home;
  private final HomeController controller;
  private final Font           defaultFont;
  private final Font           headerFooterFont;
  private int                  page;
  private final Date           printDate = new Date();
  private volatile PrintLayout printLayout;
  private boolean              fixedLabelsInitialized;
  private FixedLabel           fixedHeaderLabel;
  private FixedLabel           fixedFooterLabel;
  private FixedLabel           fixedFirstPageHeaderLabel;
  private FixedLabel           fixedFirstPageFooterLabel;
  
  /**
   * Creates a printable component that will print or display the
   * furniture view, the plan view and 3D view of the <code>home</code> 
   * managed by <code>controller</code>.
   */
  public HomePrintableComponent(Home home, HomeController controller, Font defaultFont) {
    this.home = home;
    this.controller = controller;
    this.defaultFont = defaultFont;
    this.headerFooterFont = defaultFont.deriveFont(11f);
  }

  private void initializeFixedLabels() {
    if (this.fixedLabelsInitialized) {
      return;
    }
    try {
      ResourceBundle resource = ResourceBundle.getBundle(HomePrintableComponent.class.getName());
      this.fixedHeaderLabel = getFixedHeaderOrFooterLabel(resource, "fixedHeader");
      this.fixedFooterLabel = getFixedHeaderOrFooterLabel(resource, "fixedFooter");
      this.fixedFirstPageHeaderLabel = getFixedHeaderOrFooterLabel(resource, "fixedFirstPageHeader");
      if (this.fixedFirstPageHeaderLabel == null) {
        this.fixedFirstPageHeaderLabel = this.fixedHeaderLabel;
      }
      this.fixedFirstPageFooterLabel = getFixedHeaderOrFooterLabel(resource, "fixedFirstPageFooter");
      if (this.fixedFirstPageFooterLabel == null) {
        this.fixedFirstPageFooterLabel = this.fixedFooterLabel;
      }
    } catch (MissingResourceException ex) {
      // No resource bundle
    }
    this.fixedLabelsInitialized = true;
  }

  private FixedLabel getFixedHeaderOrFooterLabel(ResourceBundle resource, String resourceKey) {
    try {        
      // Build URL base for resources referenced in fixed header or footer
      String classFile = "/" + HomePrintableComponent.class.getName().replace('.', '/') + ".properties";
      String urlBase = HomePrintableComponent.class.getResource(classFile).toString();
      urlBase = urlBase.substring(0, urlBase.length() - classFile.length());      
      
      String fixedHeaderOrFooter = String.format(resource.getString(resourceKey), urlBase);      
      JLabel fixedHeaderOrFooterLabel = new JLabel(fixedHeaderOrFooter, JLabel.CENTER);
      fixedHeaderOrFooterLabel.setFont(this.headerFooterFont);
      return new FixedLabel(fixedHeaderOrFooterLabel);
    } catch (MissingResourceException ex) {
      // No fixed label
      return null;
    }
  }
  
  /**
   * Prints a given <code>page</code>.
   */
  public int print(Graphics g, PageFormat pageFormat, int page) throws PrinterException {
    if (Thread.interrupted()) {
      throw new InterruptedPrinterException();
    }
    if (page < 0) {
      return NO_SUCH_PAGE;
    }
    PrintLayout layout = getPrintLayout(pageFormat);
    if (page >= layout.getPageCount()) {
      return NO_SUCH_PAGE;
    }

    boolean furniturePage = page < layout.furniturePageCount;
    int planIndex = page - layout.furniturePageCount;
    boolean planPage = !furniturePage && planIndex < layout.planPageCount;
    String levelName = planPage && layout.levelNames != null ? layout.levelNames.get(planIndex) : "";
    PageMargins margins;
    if (furniturePage) {
      margins = page == 0 ? layout.firstFurniturePage : layout.otherFurniturePages;
    } else if (planPage && layout.levelNames == null) {
      margins = page == 0 ? layout.firstLegacyPlanPage : layout.otherLegacyPlanPages;
    } else {
      margins = new PageMargins(layout, page == 0, levelName);
    }

    Graphics2D pageGraphics = (Graphics2D)g.create();
    try {
      pageGraphics.setFont(this.defaultFont);
      pageGraphics.setColor(Color.WHITE);
      pageGraphics.fill(new Rectangle2D.Double(0, 0, pageFormat.getWidth(), pageFormat.getHeight()));
      Graphics2D contentGraphics = (Graphics2D)pageGraphics.create();
      double[] planScale = {0};
      int result;
      try {
        PageFormat body = (PageFormat)margins.body.clone();
        contentGraphics.clip(new Rectangle2D.Double(body.getImageableX(), body.getImageableY(),
            body.getImageableWidth(), body.getImageableHeight()));
        if (furniturePage && layout.furniture instanceof FurnitureTable) {
          result = ((FurnitureTable)layout.furniture).print(contentGraphics,
              (PageFormat)layout.firstFurniturePage.body.clone(), body, page);
        } else if (furniturePage) {
          result = layout.furniture.print(contentGraphics, body, page);
        } else if (planPage && layout.plan instanceof PlanComponent) {
          result = ((PlanComponent)layout.plan).print(contentGraphics, body, planIndex,
              scale -> planScale[0] = scale);
        } else if (planPage) {
          result = layout.plan.print(contentGraphics, body, planIndex);
        } else {
          result = layout.view3D.print(contentGraphics, body, planIndex - layout.planPageCount);
        }
      } finally {
        contentGraphics.dispose();
      }
      if (result == PAGE_EXISTS) {
        printHeaderAndFooter(pageGraphics, layout, margins, page, levelName, planScale[0]);
      }
      return result;
    } finally {
      pageGraphics.dispose();
    }
  }

  private PrintLayout getPrintLayout(PageFormat pageFormat) throws PrinterException {
    PrintLayout cached = this.printLayout;
    PrintInputs inputs = callOnPrintEDT(() -> {
      initializeFixedLabels();
      PrintInputs current = new PrintInputs();
      if (cached != null && samePageFormat(cached.sourceFormat, pageFormat) && cached.inputs.matches(current)) {
        return null;
      }
      if (current.planComponent != null) {
        current.plan = current.planComponent.createPrintSnapshot(this.home.clone());
      }
      return current;
    });
    if (inputs == null) {
      return cached;
    }
    // Publish only after measuring every section. Don't hold a lock while waiting on the EDT.
    PrintLayout layout = new PrintLayout(pageFormat, inputs);
    this.printLayout = layout;
    return layout;
  }

  private static boolean samePageFormat(PageFormat first, PageFormat second) {
    return first.getOrientation() == second.getOrientation()
        && first.getWidth() == second.getWidth()
        && first.getHeight() == second.getHeight()
        && first.getImageableX() == second.getImageableX()
        && first.getImageableY() == second.getImageableY()
        && first.getImageableWidth() == second.getImageableWidth()
        && first.getImageableHeight() == second.getImageableHeight();
  }

  private static PageFormat withFixedPrintMargin(PageFormat source) {
    PageFormat format = (PageFormat)source.clone();
    try {
      String fixedPrintMargin = System.getProperty("com.eteks.sweethome3d.swing.fixedPrintMargin");
      if (fixedPrintMargin != null) {
        float margin = LengthUnit.centimeterToInch(Float.parseFloat(fixedPrintMargin)) * 72;
        Paper paper = format.getPaper();
        paper.setImageableArea(margin, margin, paper.getWidth() - 2 * margin, paper.getHeight() - 2 * margin);
        format.setPaper(paper);
      }
    } catch (NumberFormatException ex) {
      ex.printStackTrace();
    } catch (AccessControlException ex) {
      // Keep the supplied margins if the property cannot be read.
    }
    return format;
  }

  private static void setBodyArea(PageFormat format, double y, double height) {
    Paper paper = format.getPaper();
    switch (format.getOrientation()) {
      case PageFormat.LANDSCAPE:
        paper.setImageableArea(paper.getWidth() - y - height, paper.getImageableY(),
            height, paper.getImageableHeight());
        break;
      case PageFormat.REVERSE_LANDSCAPE:
        paper.setImageableArea(y, paper.getImageableY(), height, paper.getImageableHeight());
        break;
      default:
        paper.setImageableArea(paper.getImageableX(), y, paper.getImageableWidth(), height);
    }
    format.setPaper(paper);
  }

  private static int countPages(Printable printable, Graphics probe, PageMargins first, PageMargins other)
      throws PrinterException {
    int count = 0;
    if (printable != null) {
      while (printable.print(probe, (PageFormat)(count == 0 ? first.body : other.body).clone(), count) == PAGE_EXISTS) {
        count++;
      }
    }
    return count;
  }

  /**
   * Runs a printing operation on the EDT and waits for cleanup before returning its graphics to the caller.
   */
  static <T> T callOnPrintEDT(Callable<T> operation) throws PrinterException {
    FutureTask<T> task = new FutureTask<T>(operation);
    if (EventQueue.isDispatchThread()) {
      task.run();
    } else {
      EventQueue.invokeLater(task);
    }
    boolean interrupted = false;
    try {
      for (;;) {
        try {
          T result = task.get();
          if (interrupted) {
            throw new InterruptedPrinterException("Print interrupted");
          }
          return result;
        } catch (InterruptedException ex) {
          interrupted = true;
        } catch (ExecutionException ex) {
          Throwable cause = ex.getCause();
          if (cause instanceof PrinterException) {
            throw (PrinterException)cause;
          } else if (cause instanceof RuntimeException) {
            throw (RuntimeException)cause;
          } else if (cause instanceof Error) {
            throw (Error)cause;
          }
          PrinterException failure = new PrinterException("EDT printing operation failed");
          failure.initCause(cause);
          throw failure;
        }
      }
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private static Printable asPrintable(Object view) {
    return view instanceof Printable ? (Printable)view : null;
  }

  // Capture live Swing and model inputs together. Clone the plan only on a cache miss.
  private final class PrintInputs {
    final HomePrint settings = home.getPrint();
    final String homeName = home.getName() == null ? "" : home.getName();
    final Printable furniture = this.settings == null || this.settings.isFurniturePrinted()
        ? asPrintable(controller.getFurnitureController().getView()) : null;
    final Printable view3D = this.settings == null || this.settings.isView3DPrinted()
        ? asPrintable(controller.getHomeController3D().getView()) : null;
    final PlanView planView = this.settings == null || this.settings.isPlanPrinted()
        ? controller.getPlanController().getView() : null;
    final PlanComponent planComponent = this.planView instanceof MultipleLevelsPlanPanel
        ? ((MultipleLevelsPlanPanel)this.planView).getPlanComponent()
        : this.planView instanceof PlanComponent ? (PlanComponent)this.planView : null;
    final long planRevision = this.planComponent == null ? 0 : this.planComponent.getPrintRevision();
    final List<String> levelNames;
    final long furnitureRevision;
    final List<Object> furnitureTableState;
    Printable plan = asPrintable(this.planView);

    PrintInputs() {
      this.levelNames = this.planComponent == null || home.getLevels().isEmpty() ? null : new ArrayList<String>();
      if (this.levelNames != null) {
        for (Level level : home.getLevels()) {
          if (level.isViewable()) {
            this.levelNames.add(level.getName());
          }
        }
      }
      FurnitureTable table = this.furniture instanceof FurnitureTable ? (FurnitureTable)this.furniture : null;
      this.furnitureRevision = table == null ? 0 : table.getPrintRowRevision();
      this.furnitureTableState = table == null ? null : table.getPrintTableState();
    }

    boolean matches(PrintInputs other) {
      return this.settings == other.settings && this.homeName.equals(other.homeName)
          && this.furniture == other.furniture && this.view3D == other.view3D && this.planView == other.planView
          && this.planRevision == other.planRevision && Objects.equals(this.levelNames, other.levelNames)
          && this.furnitureRevision == other.furnitureRevision && Objects.equals(this.furnitureTableState, other.furnitureTableState);
    }
  }

  private final class PrintLayout {
    final PrintInputs inputs;
    final PageFormat sourceFormat;
    final PageFormat pageFormat;
    final HomePrint settings;
    final String homeName;
    final String presentationName;
    final FontMetrics metrics;
    final Printable furniture;
    final Printable plan;
    final Printable view3D;
    final List<String> levelNames;
    final PageMargins firstFurniturePage;
    final PageMargins otherFurniturePages;
    final PageMargins firstLegacyPlanPage;
    final PageMargins otherLegacyPlanPages;
    final int furniturePageCount;
    final int planPageCount;
    final int view3DPageCount;

    PrintLayout(PageFormat source, PrintInputs inputs) throws PrinterException {
      this.inputs = inputs;
      this.sourceFormat = (PageFormat)source.clone();
      this.pageFormat = withFixedPrintMargin(source);
      this.settings = inputs.settings;
      this.homeName = inputs.homeName;
      ContentManager contentManager = controller.getContentManager();
      this.presentationName = contentManager == null ? this.homeName
          : contentManager.getPresentationName(this.homeName, ContentManager.ContentType.SWEET_HOME_3D);
      this.furniture = inputs.furniture;
      this.plan = inputs.plan;
      this.levelNames = inputs.levelNames;
      this.view3D = inputs.view3D;

      Graphics2D probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics();
      try {
        probe.setFont(defaultFont);
        // Use the same text line height when measuring and rendering, regardless of graphics device.
        this.metrics = probe.getFontMetrics(headerFooterFont);
        this.firstFurniturePage = new PageMargins(this, true, "");
        this.otherFurniturePages = new PageMargins(this, false, "");
        this.furniturePageCount = this.furniture instanceof FurnitureTable
            ? ((FurnitureTable)this.furniture).getPrintPageCount(this.firstFurniturePage.body, this.otherFurniturePages.body)
            : countPages(this.furniture, probe, this.firstFurniturePage, this.otherFurniturePages);

        if (this.levelNames == null && this.plan != null && this.furniturePageCount == 0) {
          this.firstLegacyPlanPage = new PageMargins(this, true, "");
          PageMargins following = new PageMargins(this, false, "");
          if (this.plan instanceof PlanComponent && this.settings != null && this.settings.getPlanScale() != null) {
            // Use the same height for every tile to avoid overlaps and gaps.
            double height = Math.min(this.firstLegacyPlanPage.body.getImageableHeight(), following.body.getImageableHeight());
            setBodyArea(this.firstLegacyPlanPage.body, this.firstLegacyPlanPage.body.getImageableY(), height);
            setBodyArea(following.body, following.body.getImageableY(), height);
          }
          this.planPageCount = countPages(this.plan, probe, this.firstLegacyPlanPage, following);
          this.otherLegacyPlanPages = following;
        } else {
          this.firstLegacyPlanPage = this.firstFurniturePage;
          this.otherLegacyPlanPages = this.otherFurniturePages;
          this.planPageCount = this.levelNames == null
              ? countPages(this.plan, probe, this.otherLegacyPlanPages, this.otherLegacyPlanPages)
              : this.levelNames.size();
        }

        PageMargins first3DPage = this.furniturePageCount + this.planPageCount == 0
            ? this.firstFurniturePage : this.otherFurniturePages;
        this.view3DPageCount = countPages(this.view3D, probe, first3DPage, this.otherFurniturePages);
      } finally {
        probe.dispose();
      }
    }

    int getPageCount() {
      return this.furniturePageCount + this.planPageCount + this.view3DPageCount;
    }

    Object[] headerValues(int page, int pageCount, String scale, String levelName) {
      return new Object[] {page + 1, pageCount, scale, printDate, this.presentationName, this.homeName, levelName};
    }
  }

  private final class PageMargins {
    final PageFormat body;
    final FixedLabel fixedHeader;
    final FixedLabel fixedFooter;
    final int fixedHeaderHeight;
    final int fixedFooterHeight;
    final float headerY;
    final float footerY;

    PageMargins(PrintLayout layout, boolean first, String levelName) {
      this.body = (PageFormat)layout.pageFormat.clone();
      this.fixedHeader = first ? fixedFirstPageHeaderLabel : fixedHeaderLabel;
      this.fixedFooter = first ? fixedFirstPageFooterLabel : fixedFooterLabel;
      this.fixedHeaderHeight = this.fixedHeader == null ? 0 : this.fixedHeader.height;
      this.fixedFooterHeight = this.fixedFooter == null ? 0 : this.fixedFooter.height;
      double top = this.body.getImageableY();
      double bottom = top + this.body.getImageableHeight();
      if (this.fixedHeader != null) {
        top += this.fixedHeaderHeight + HEADER_FOOTER_MARGIN;
      }
      if (this.fixedFooter != null) {
        bottom -= this.fixedFooterHeight + HEADER_FOOTER_MARGIN;
      }
      this.headerY = (float)(top + layout.metrics.getAscent());
      this.footerY = (float)(bottom - layout.metrics.getDescent());
      if (layout.settings != null) {
        // Placeholder counts and scale have the same blank/nonblank status as their final text.
        Object[] values = layout.headerValues(0, 1, "?", levelName);
        float lineHeight = layout.metrics.getAscent() + layout.metrics.getDescent() + HEADER_FOOTER_MARGIN;
        if (formatText(layout.settings.getHeaderFormat(), values) != null) {
          top += lineHeight;
        }
        if (formatText(layout.settings.getFooterFormat(), values) != null) {
          bottom -= lineHeight;
        }
      }
      setBodyArea(this.body, top, Math.max(0, bottom - top));
    }
  }

  private static String formatText(String template, Object[] values) {
    if (template == null) {
      return null;
    }
    String text = Variable.getMessageFormat(template).format(values).trim();
    return text.isEmpty() ? null : text;
  }

  private void printHeaderAndFooter(Graphics2D graphics, PrintLayout layout, PageMargins margins,
                                    int page, String levelName, double scale) throws PrinterException {
    graphics.setFont(this.headerFooterFont);
    graphics.setColor(Color.BLACK);
    PageFormat format = layout.pageFormat;
    double x = format.getImageableX();
    if (margins.fixedHeader != null) {
      printFixedLabel(graphics, margins.fixedHeader.label, x, format.getImageableY(),
          (int)format.getImageableWidth(), margins.fixedHeaderHeight);
    }
    if (layout.settings != null) {
      String scaleText = "?";
      if (scale > 0 && Double.isFinite(scale)) {
        NumberFormat denominator = NumberFormat.getNumberInstance();
        denominator.setMaximumFractionDigits(4);
        denominator.setGroupingUsed(false);
        scaleText = "1/" + denominator.format(1 / scale);
      }
      Object[] values = layout.headerValues(page, layout.getPageCount(), scaleText, levelName);
      printCenteredText(graphics, formatText(layout.settings.getHeaderFormat(), values), format, margins.headerY);
      printCenteredText(graphics, formatText(layout.settings.getFooterFormat(), values), format, margins.footerY);
    }
    if (margins.fixedFooter != null) {
      printFixedLabel(graphics, margins.fixedFooter.label, x,
          format.getImageableY() + format.getImageableHeight() - margins.fixedFooterHeight,
          (int)format.getImageableWidth(), margins.fixedFooterHeight);
    }
  }

  private static void printCenteredText(Graphics2D graphics, String text, PageFormat format, float y) {
    if (text != null) {
      graphics.drawString(text, (float)(format.getWidth() - graphics.getFontMetrics().stringWidth(text)) / 2, y);
    }
  }

  private static final class FixedLabel {
    final JLabel label;
    final int height;

    FixedLabel(JLabel label) {
      this.label = label;
      Dimension size = label.getPreferredSize();
      this.height = size.height;
      label.setSize(size);
    }
  }

  private static void printFixedLabel(Graphics2D graphics, JLabel label, double x, double y, int width, int height)
      throws PrinterException {
    callOnPrintEDT(() -> {
      Graphics2D labelGraphics = (Graphics2D)graphics.create();
      try {
        Dimension oldSize = label.getSize();
        try {
          label.setSize(width, height);
          labelGraphics.translate(x, y);
          label.print(labelGraphics);
        } finally {
          label.setSize(oldSize);
        }
      } finally {
        labelGraphics.dispose();
      }
      return null;
    });
  }

  /**
   * Returns the preferred size of this component according to paper orientation and size
   * of home print attributes.
   */
  @Override
  public Dimension getPreferredSize() {
    PageFormat pageFormat = getPageFormat(this.home.getPrint());
    double maxSize = Math.max(pageFormat.getWidth(), pageFormat.getHeight());
    Insets insets = getInsets();
    int maxPreferredSize = Math.round(400 * SwingTools.getResolutionScale());
    return new Dimension((int)(pageFormat.getWidth() / maxSize * maxPreferredSize) + insets.left + insets.right, 
        (int)(pageFormat.getHeight() / maxSize * maxPreferredSize) + insets.top + insets.bottom);
  }
  
  /**
   * Paints the current page.
   */
  @Override
  protected void paintComponent(Graphics g) {
    Graphics2D g2D = (Graphics2D)g.create();
    try {
      // Print printable object at component's scale
      PageFormat pageFormat = getPageFormat(this.home.getPrint());
      Insets insets = getInsets();
      double scale = (getWidth() - insets.left - insets.right) / pageFormat.getWidth();
      g2D.scale(scale, scale);
      print(g2D, pageFormat, this.page);
    } catch (PrinterException ex) {
      throw new RuntimeException(ex);
    } finally {
      g2D.dispose();
    }
  }
  
  /**
   * Sets the page currently painted by this component.
   */
  public void setPage(int page) {
    if (this.page != page) {
      this.page = page;
      repaint();
    }
  }
  
  /**
   * Returns the page currently painted by this component.
   */
  public int getPage() {
    return this.page;
  }

  /**
   * Returns the page count of the home printed by this component. 
   */
  public int getPageCount() {
    try {
      return getPageCount(getPageFormat(this.home.getPrint()));
    } catch (PrinterException ex) {
      throw new IllegalStateException("Couldn't count print pages", ex);
    }
  }

  int getPageCount(PageFormat format) throws PrinterException {
    return getPrintLayout(format).getPageCount();
  }

  /**
   * Returns a <code>PageFormat</code> object created from <code>homePrint</code>.
   */
  public static PageFormat getPageFormat(HomePrint homePrint) {
    final PrinterJob printerJob = PrinterJob.getPrinterJob();
    if (homePrint == null) {
      return printerJob.defaultPage();
    } else {
      PageFormat pageFormat = new PageFormat();
      switch (homePrint.getPaperOrientation()) {
        case PORTRAIT :
          pageFormat.setOrientation(PageFormat.PORTRAIT);
          break;
        case LANDSCAPE :
          pageFormat.setOrientation(PageFormat.LANDSCAPE);
          break;
        case REVERSE_LANDSCAPE :
          pageFormat.setOrientation(PageFormat.REVERSE_LANDSCAPE);
          break;
      }
      Paper paper = new Paper();
      paper.setSize(homePrint.getPaperWidth(), homePrint.getPaperHeight());
      paper.setImageableArea(homePrint.getPaperLeftMargin(), homePrint.getPaperTopMargin(), 
          homePrint.getPaperWidth() - homePrint.getPaperLeftMargin() - homePrint.getPaperRightMargin(), 
          homePrint.getPaperHeight() - homePrint.getPaperTopMargin() - homePrint.getPaperBottomMargin());
      pageFormat.setPaper(paper);
      pageFormat = printerJob.validatePage(pageFormat);
      return pageFormat;
    }
  }
}