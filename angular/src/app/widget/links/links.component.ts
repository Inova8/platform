import { Component, Inject, OnInit } from '@angular/core';
import { Util } from '../../util';
import { DJDataGetOptions } from '../../djbase/data';
import { DJBaseComponent } from '../../djbase/djbase.component';
import { TextComponent } from '../text/text.component';
import { DashjoinWidget } from '../widget-registry';
import { Table } from '../../model';
import { MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';

/**
 * displays links to related records
 */
@DashjoinWidget({
  name: 'links',
  category: 'Default',
  description: 'Component that displays links to related records',
  htmlTag: 'dj-links',
  fields: ['title']
})
@Component({
  selector: 'app-links',
  templateUrl: './links.component.html',
  styleUrls: ['./links.component.css']
})
export class LinksComponent extends DJBaseComponent implements OnInit {

  /**
   * incoming links
   */
  incoming: {};

  /**
   * get schema (for displaying links) and display initial pagination
   */
  async initWidget() {
    if (this.layout.schema) {
      this.schema = this.layout.schema;
    } else {
      this.app.getSchema(this.database, this.table).subscribe(res2 => {
        this.schema = JSON.parse(JSON.stringify(res2));
      }, this.errorHandler);
    }
    this.pageIncoming({ pageIndex: 0, pageSize: 10 });
  }

  /**
   * pagination event for incoming links
   */
  pageIncoming(event: any) {
    if (!this.getKey()) { return; }
    const offset = event.pageIndex * event.pageSize;
    const limit = event.pageSize;

    const opts: DJDataGetOptions = { pageSize: limit, cursor: { cursor: offset } };

    this.getData().incoming(this.getKey(), opts).then(res => {
      this.incoming = {};
      let length = 0;
      for (const i of res.data) {
        const parts = Util.parseColumnID(i.fk);
        const key = parts[2] + '.' + parts[3];
        if (!this.incoming[key]) {
          this.incoming[key] = [];
        }
        this.incoming[key].push(i);
        length = Math.max(length, this.incoming[key].length);
      }
      if (length < limit) {
        this.allLength = offset + res.data.length;
      }
    }, this.errorHandler);
  }

  /**
   * splits array into equal size chuncks
   */
  splitArray(array: any[], chunk: number): any[][] {
    const res = [];
    for (let i = 0; i < array.length; i += chunk) {
      res.push(array.slice(i, i + chunk));
    }
    return res;
  }

  /**
   * expose Array.isArray to template
   */
  isArray(a: any): boolean {
    return Array.isArray(a);
  }

  /**
   * wrap outgoing link array element in object: {col: value} for consumption by link method
   */
  pseudoObject(key: string, value: any): object {
    const res = {};
    res[key] = value;
    return res;
  }

  /**
   * wrap outgoing link array schema in {col: items} for consumption by link method
   */
  pseudoSchema(key: string, value: any): Table {
    const res = {};
    res[key] = value.properties[key].items;
    return { properties: res } as Table;
  }

  /**
   * return true if this value is a normal value (no key)
   */
  noLink(table: Table, column: string, data: object): boolean {
    return null === this.link(table, column, data);
  }

  /**
   * true if the link points to another DJ
   */
  externalLink(table: Table, column: string, data: object): boolean {
    const link = this.link(table, column, data);
    if (link === null) {
      return false;
    }
    return link[0].startsWith('http://') || link[0].startsWith('https://');
  }

  /**
   * true if link to same DJ
   */
  internalLink(table: Table, column: string, data: object): boolean {
    const link = this.link(table, column, data);
    if (link === null) {
      return false;
    }
    return !(link[0].startsWith('http://') || link[0].startsWith('https://'));
  }

  /**
   * true if FK link to same DJ
   */
  internalFkLink(table: Table, column: string, data: object): boolean {
    const link = this.link(table, column, data);
    if (link === null) {
      return false;
    }
    if (link[0].startsWith('http://') || link[0].startsWith('https://')) {
      return false;
    }
    if (table.properties[column]) {
      if (table.properties[column].ref) {
        return true;
      }
    }
    return false;
  }

  /**
   * return link or null if normal literal value
   * 
   * @preferPk  there can be cases, where a field is both a PK and a FK. 
   * If that is the case, we could generate two legal links here. The UI may give us a hint as to 
   * what kind is preferred (on the table select * view, we prefer PKs, otherwise we take the FK first)
   */
  link(table: Table, column: string, data: object, preferPk?: boolean): string[] {

    const value = data[column];

    if (!value && value !== 0) {
      return null;
    }

    if (Object.keys(value).length === 3 && value.database && value.table && value.pk?.[0]) {
      return ['/resource', value.database, value.table, value.pk[0]];
    }

    if (!table) {
      return null;
    }

    // if we do not prefer PKs, a FK has perference
    if (!preferPk && table.properties[column] && table.properties[column].ref) {
      const parts = Util.parseColumnID(table.properties[column].ref);
      if (parts[1] === 'config' && parts[2] === 'Table') {
        return ['/table', Util.parseTableID(value)[1], Util.parseTableID(value)[2]];
      } else {
        return ['/resource', parts[1], parts[2], value];
      }
    }
    // try PK
    if (table.properties[column] && table.properties[column].pkpos !== null && table.properties[column].pkpos >= 0) {
      const parts = Util.parseTableID(table.properties[column].parent);
      if (parts[1] === 'config' && parts[2] === 'Table') {
        return ['/table', Util.parseTableID(value)[1], Util.parseTableID(value)[2]];
      } else {
        for (const p of Object.values(table.properties)) {
          if (p.pkpos > 0) {
            // table does not yet support linking to composite keys
            return null;
          }
        }
        return ['/resource', parts[1], parts[2], value];
      }
    }
    // we tried PK, now try FK (if it was not already handled)
    if (table.properties[column] && table.properties[column].ref) {
      const parts = Util.parseColumnID(table.properties[column].ref);
      if (parts[1] === 'config' && parts[2] === 'Table') {
        return ['/table', Util.parseTableID(value)[1], Util.parseTableID(value)[2]];
      } else {
        return ['/resource', parts[1], parts[2], value];
      }
    }
    return null;
  }

  /**
   * translate /resource/db/table/a%2fb to [resource, db, table, a/b]
   */
  toRouterLink(link: string): string[] {
    const res = link.split('/').map(p => decodeURIComponent(p));
    if (link.startsWith('/resource/config/Table/')) {
      const table = Util.parseTableID(res[4]);
      table[0] = '/table';
      return table;
    }
    return res;
  }

  /**
   * delegate to logic in text component
   */
  linkArray(s: string): string[] {
    return new TextComponent(this.elRef, this.cdRef, this.route, this.router).linkArray(s);
  }

  /**
   * translate resource structure to [string]
   */
  linkResource(r: any): string[] {
    if (r.database === 'config' && r.table === 'Table') {
      const table = Util.parseTableID(r.pk[0]);
      table[0] = '/table';
      return table;
    } else {
      const res = ['/resource', r.database, r.table];
      for (const x of r.pk) {
        res.push(x)
      }
      return res;
    }
  }

  /**
   * generate tootips for long strings and objects
   */
  matTooltip(value: any, neverSkip?: boolean) {
    if (value && typeof (value) === 'object') {
      return JSON.stringify(value, null, 2);
    }
    if (typeof (value) === 'string') {
      if (value.length > 20) {
        return value;
      }
    }
    return neverSkip ? value : null;
  }

  /**
   * popup for JSON objects
   */
  jsonDialog(value: any) {
    if (typeof (value) === 'object') {
      this.dialog.open(JsonCellDialogCopyComponent, { data: JSON.stringify(value, null, 2) });
    }
  }
}

/**
 * dialog that shows a pretty-printed version of a JSON object
 */
@Component({
  template: `
    <mat-dialog-content>
      <pre>{{data}}</pre>
    </mat-dialog-content>
  `
})
export class JsonCellDialogCopyComponent {
  /**
   * constructure that gets the pretty printed JSON object to be displayed in pre tags
   * @param dialogRef dislog ref
   * @param data pretty printed JSON object
   */
  constructor(
    public dialogRef: MatDialogRef<JsonCellDialogCopyComponent>,
    @Inject(MAT_DIALOG_DATA) public data: string) { }
}
