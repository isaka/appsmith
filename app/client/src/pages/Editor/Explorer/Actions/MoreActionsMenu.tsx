import React, { useCallback, useEffect, useState } from "react";
import { useDispatch, useSelector } from "react-redux";

import type { AppState } from "@appsmith/reducers";

import {
  moveActionRequest,
  copyActionRequest,
  deleteAction,
} from "actions/pluginActionActions";

import {
  CONTEXT_COPY,
  CONTEXT_DELETE,
  CONFIRM_CONTEXT_DELETE,
  CONTEXT_MOVE,
  createMessage,
} from "@appsmith/constants/messages";
import {
  Button,
  Menu,
  MenuContent,
  MenuItem,
  MenuSub,
  MenuSubContent,
  MenuSubTrigger,
  MenuTrigger,
} from "design-system";
import { useToggle } from "@mantine/hooks";

interface EntityContextMenuProps {
  id: string;
  name: string;
  className?: string;
  pageId: string;
  isChangePermitted?: boolean;
  isDeletePermitted?: boolean;
}

export function MoreActionsMenu(props: EntityContextMenuProps) {
  const [isMenuOpen, toggleMenuOpen] = useToggle([false, true]);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const { isChangePermitted = false, isDeletePermitted = false } = props;

  useEffect(() => {
    if (!isMenuOpen) setConfirmDelete(false);
  }, [isMenuOpen]);

  const dispatch = useDispatch();
  const copyActionToPage = useCallback(
    (actionId: string, actionName: string, pageId: string) =>
      dispatch(
        copyActionRequest({
          id: actionId,
          destinationPageId: pageId,
          name: actionName,
        }),
      ),
    [dispatch],
  );
  const moveActionToPage = useCallback(
    (actionId: string, actionName: string, destinationPageId: string) =>
      dispatch(
        moveActionRequest({
          id: actionId,
          destinationPageId,
          originalPageId: props.pageId,
          name: actionName,
        }),
      ),
    [dispatch, props.pageId],
  );
  const deleteActionFromPage = useCallback(
    (actionId: string, actionName: string) => {
      dispatch(deleteAction({ id: actionId, name: actionName }));
      // Reset the delete confirmation state because it can navigate to another action
      // which will not remount this component
      setConfirmDelete(false);
      toggleMenuOpen(false);
    },
    [dispatch],
  );

  const menuPages = useSelector((state: AppState) => {
    return state.entities.pageList.pages.map((page) => ({
      label: page.pageName,
      id: page.pageId,
      value: page.pageName,
    }));
  });

  return isChangePermitted || isDeletePermitted ? (
    <Menu
      className={props.className}
      onOpenChange={() => toggleMenuOpen()}
      open={isMenuOpen}
    >
      <MenuTrigger>
        <Button
          data-testid="more-action-trigger"
          isIconButton
          kind="tertiary"
          size="md"
          startIcon="context-menu"
        />
      </MenuTrigger>
      <MenuContent loop style={{ zIndex: 100 }} width="200px">
        {isChangePermitted && (
          <MenuSub>
            <MenuSubTrigger startIcon="duplicate">
              {createMessage(CONTEXT_COPY)}
            </MenuSubTrigger>
            <MenuSubContent>
              {menuPages.map((page) => {
                return (
                  <MenuItem
                    key={page.id}
                    // eslint-disable-next-line @typescript-eslint/ban-ts-comment
                    //@ts-ignore
                    onSelect={() =>
                      copyActionToPage(props.id, props.name, page.id)
                    }
                  >
                    {page.label}
                  </MenuItem>
                );
              })}
            </MenuSubContent>
          </MenuSub>
        )}
        {isChangePermitted && (
          <MenuSub>
            <MenuSubTrigger startIcon="swap-horizontal">
              {createMessage(CONTEXT_MOVE)}
            </MenuSubTrigger>
            <MenuSubContent>
              {/* Isn't it better ux to perform this check outside the menu and then simply not show the option?*/}
              {menuPages.length > 1 ? (
                menuPages
                  .filter((page) => page.id !== props.pageId) // Remove current page from the list
                  .map((page) => {
                    return (
                      <MenuItem
                        key={page.id}
                        // eslint-disable-next-line @typescript-eslint/ban-ts-comment
                        //@ts-ignore
                        onSelect={() =>
                          moveActionToPage(props.id, props.name, page.id)
                        }
                      >
                        {page.label}
                      </MenuItem>
                    );
                  })
              ) : (
                <MenuItem key="no-pages">No pages</MenuItem>
              )}
            </MenuSubContent>
          </MenuSub>
        )}
        {isDeletePermitted && (
          <MenuItem
            className="t--apiFormDeleteBtn error-menuitem"
            // eslint-disable-next-line @typescript-eslint/ban-ts-comment
            //@ts-ignore
            onSelect={(e: Event) => {
              e.preventDefault();
              confirmDelete
                ? deleteActionFromPage(props.id, props.name)
                : setConfirmDelete(true);
            }}
            startIcon="trash"
          >
            {confirmDelete
              ? createMessage(CONFIRM_CONTEXT_DELETE)
              : createMessage(CONTEXT_DELETE)}
          </MenuItem>
        )}
      </MenuContent>
    </Menu>
  ) : null;
}

export default MoreActionsMenu;
