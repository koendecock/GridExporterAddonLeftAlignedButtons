/**
 * 
 */
package com.flowingcode.vaadin.addons.gridexporter;

import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.lang.reflect.Method;
import java.util.Calendar;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellAddress;
import org.apache.poi.ss.util.CellRangeAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.vaadin.flow.data.binder.BeanPropertySet;
import com.vaadin.flow.data.binder.PropertySet;
import com.vaadin.flow.data.provider.DataCommunicator;
import com.vaadin.flow.data.provider.DataProvider;
import com.vaadin.flow.data.provider.Query;

/**
 * @author mlope
 *
 */
@SuppressWarnings("serial")
class ExcelInputStreamFactory<T> extends BaseInputStreamFactory<T> {

  private final static Logger LOGGER = LoggerFactory.getLogger(ExcelInputStreamFactory.class);
  private static final String DEFAULT_TEMPLATE= "/template.xlsx";
  
  public ExcelInputStreamFactory(GridExporter<T> exporter, String template) {
    super(exporter, template, DEFAULT_TEMPLATE);
  }

  @Override
  public InputStream createInputStream() {
    PipedInputStream in = new PipedInputStream();
    try {
      exporter.columns =
          exporter.grid.getColumns().stream().filter(this::isExportable).collect(Collectors.toList());
      Workbook wb = getBaseTemplateWorkbook();
      Sheet sheet = wb.getSheetAt(exporter.sheetNumber);

      Cell titleCell = findCellWithPlaceHolder(sheet, exporter.titlePlaceHolder);
      titleCell.setCellValue(exporter.title);

      Cell cell = findCellWithPlaceHolder(sheet, exporter.headersPlaceHolder);
      List<String> headers = getGridHeaders(exporter.grid);

      fillHeaderOrFooter(sheet, cell, headers);
      if (exporter.autoMergeTitle) {
        sheet.addMergedRegion(
            new CellRangeAddress(titleCell.getRowIndex(), titleCell.getRowIndex(),
                titleCell.getColumnIndex(), titleCell.getColumnIndex() + headers.size() - 1));
      }

      cell = findCellWithPlaceHolder(sheet, exporter.dataPlaceHolder);
      fillData(sheet, cell, exporter.grid.getDataProvider());

      cell = findCellWithPlaceHolder(sheet, exporter.footersPlaceHolder);
      List<String> footers = getGridFooters(exporter.grid);
      fillHeaderOrFooter(sheet, cell, footers);

      exporter.additionalPlaceHolders.entrySet().forEach(entry -> {
        Cell cellwp = findCellWithPlaceHolder(sheet, entry.getKey());
        cellwp.setCellValue(entry.getValue());
      });

      final PipedOutputStream out = new PipedOutputStream(in);
      new Thread(new Runnable() {
        public void run() {
          try {
            wb.write(out);
          } catch (IOException e) {
            LOGGER.error("Problem generating export", e);
          } finally {
            if (out != null) {
              try {
                out.close();
              } catch (IOException e) {
                LOGGER.error("Problem generating export", e);
              }
            }
          }
        }
      }).start();
    } catch (IOException e) {
      LOGGER.error("Problem generating export", e);
    }
    return in;
  }
  

  @SuppressWarnings("unchecked")
  private void fillData(Sheet sheet, Cell dataCell, DataProvider<T, ?> dataProvider) {
    Object filter = null;
    try {
      Method method = DataCommunicator.class.getDeclaredMethod("getFilter");
      method.setAccessible(true);
      filter = method.invoke(exporter.grid.getDataCommunicator());
    } catch (Exception e) {
      LOGGER.error("Unable to get filter from DataCommunicator", e);
    }

    @SuppressWarnings("rawtypes")
    Query<T, ?> streamQuery = new Query<>(0, exporter.grid.getDataProvider().size(new Query(filter)),
        exporter.grid.getDataCommunicator().getBackEndSorting(),
        exporter.grid.getDataCommunicator().getInMemorySorting(), null);
    Stream<T> dataStream = getDataStream(streamQuery);

    boolean[] notFirstRow = new boolean[1];
    Cell[] startingCell = new Cell[1];
    startingCell[0] = dataCell;
    dataStream.forEach(t -> {
      if (notFirstRow[0]) {
        CellStyle cellStyle = startingCell[0].getCellStyle();
        int lastRow = sheet.getLastRowNum();
        sheet.shiftRows(startingCell[0].getRowIndex() + 1, lastRow, 1);
        Row newRow = sheet.createRow(startingCell[0].getRowIndex() + 1);
        startingCell[0] = newRow.createCell(startingCell[0].getColumnIndex());
        startingCell[0].setCellStyle(cellStyle);
      }
      buildRow(t, sheet, startingCell[0]);
      notFirstRow[0] = true;
    });

  }

  @SuppressWarnings("unchecked")
  private void buildRow(T item, Sheet sheet, Cell startingCell) {
    if (exporter.propertySet == null) {
      exporter.propertySet = (PropertySet<T>) BeanPropertySet.get(item.getClass());
    }
    if (exporter.columns.isEmpty())
      throw new IllegalStateException("Grid has no columns");

    int[] currentColumn = new int[1];
    currentColumn[0] = startingCell.getColumnIndex();
    exporter.columns.forEach(column -> {
      Object value = exporter.extractValueFromColumn(item, column);
      Cell currentCell = startingCell;
      if (startingCell.getColumnIndex() < currentColumn[0]) {
        currentCell = startingCell.getRow().createCell(currentColumn[0]);
        currentCell.setCellStyle(startingCell.getCellStyle());
      }
      currentColumn[0] = currentColumn[0] + 1;
      buildCell(value, currentCell);

    });
  }



  private void buildCell(Object value, Cell cell) {
    if (value == null) {
      cell.setBlank();
    } else if (value instanceof Boolean) {
      cell.setCellValue((Boolean) value);
    } else if (value instanceof Calendar) {
      Calendar calendar = (Calendar) value;
      cell.setCellValue(calendar.getTime());
    } else if (value instanceof Double) {
      cell.setCellValue((Double) value);
    } else {
      cell.setCellValue(value.toString());
    }
  }

  private Workbook getBaseTemplateWorkbook() throws EncryptedDocumentException, IOException {
    InputStream inp = this.getClass().getResourceAsStream(template);
    return WorkbookFactory.create(inp);
  }

  private Cell findCellWithPlaceHolder(Sheet sheet, String placeholder) {
    for (Row row : sheet) {
      for (Cell cell : row) {
        if (cell.getCellType() == CellType.STRING) {
          if (cell.getRichStringCellValue().getString().trim().equals(placeholder)) {
            return cell;
          }
        }
      }
    }
    return null;
  }

  private void fillHeaderOrFooter(Sheet sheet, Cell headersCell, List<String> headers) {
    CellStyle style = headersCell.getCellStyle();
    sheet.setActiveCell(headersCell.getAddress());
    headers.forEach(header -> {
      Cell cell =
          sheet.getRow(sheet.getActiveCell().getRow()).getCell(sheet.getActiveCell().getColumn());
      if (cell == null) {
        cell = sheet.getRow(sheet.getActiveCell().getRow())
            .createCell(sheet.getActiveCell().getColumn());
        cell.setCellStyle(style);
      }
      cell.setCellValue(header);
      sheet.setActiveCell(
          new CellAddress(sheet.getActiveCell().getRow(), sheet.getActiveCell().getColumn() + 1));
    });
  }
}
