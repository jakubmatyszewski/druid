/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { Classes, Icon, InputGroup, Menu, MenuItem, Popover } from '@blueprintjs/core';
import { IconNames } from '@blueprintjs/icons';
import type { ExpressionMeta } from '@druid-toolkit/visuals-core';
import classNames from 'classnames';
import React, { useState } from 'react';

import { caseInsensitiveContains, dataTypeToIcon, filterMap } from '../../../utils';
import { DragHelper } from '../drag-helper';
import type { Dataset } from '../utils';

import './resource-pane.scss';

export interface ResourcePaneProps {
  dataset: Dataset;
  onFilter?: (column: ExpressionMeta) => void;
  onShow?: (column: ExpressionMeta) => void;
}

export const ResourcePane = function ResourcePane(props: ResourcePaneProps) {
  const { dataset, onFilter, onShow } = props;
  const [columnSearch, setColumnSearch] = useState('');

  const { columns } = dataset;

  return (
    <div className="resource-pane">
      <InputGroup
        className="search-input"
        value={columnSearch}
        onChange={e => setColumnSearch(e.target.value)}
        placeholder="Search..."
      />
      <div className="resource-items">
        {filterMap(columns, (c, i) => {
          if (!caseInsensitiveContains(c.name, columnSearch)) return;
          return (
            <Popover
              className="resource-item"
              key={i}
              position="right"
              content={
                <Menu>
                  {onFilter && (
                    <MenuItem icon={IconNames.FILTER} text="Filter" onClick={() => onFilter(c)} />
                  )}
                  {onShow && (
                    <MenuItem icon={IconNames.EYE_OPEN} text="Show" onClick={() => onShow(c)} />
                  )}
                </Menu>
              }
            >
              <div
                className={Classes.MENU_ITEM}
                draggable
                onDragStart={e => {
                  e.dataTransfer.effectAllowed = 'all';
                  DragHelper.dragColumn = c;
                  DragHelper.createDragGhost(e.dataTransfer, c.name);
                }}
              >
                <Icon
                  className={Classes.MENU_ITEM_ICON}
                  icon={c.sqlType ? dataTypeToIcon(c.sqlType) : IconNames.BLANK}
                />
                <div className={classNames(Classes.FILL, Classes.TEXT_OVERFLOW_ELLIPSIS)}>
                  {c.name}
                </div>
              </div>
            </Popover>
          );
        })}
      </div>
    </div>
  );
};
