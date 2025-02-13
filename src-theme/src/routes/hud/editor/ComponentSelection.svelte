<script lang="ts">
    import type {ComponentFactories} from "../../../integration/types";
    import {slide} from "svelte/transition";
    import {cubicOut} from "svelte/easing";
    import Search from "../../menu/common/Search.svelte";
    import {onMount} from "svelte";
    import {createComponent, getComponentFactories} from "../../../integration/rest";

    let componentFactories: ComponentFactories[] = $state([]);
    onMount(async () => {
        componentFactories = await getComponentFactories();
    });

    let searchQuery = "";
    let activeTab = $state("Components");
    let isExpanded = $state(true);
    const tabs = ["Components", "Color Schemes", "Effects"];

    function handleSearch(e: CustomEvent<{ query: string }>) {
        searchQuery = e.detail.query;
    }

    function setDefaultLayout(themeName: string) {
        console.log(`Setting default layout for theme: ${themeName}`);
    }

    function toggleExpand() {
        isExpanded = !isExpanded;
    }
</script>

<div class="factories-wrapper" class:expanded={isExpanded}>
    <button
            class="expand-toggle"
            onclick={toggleExpand}
            class:expanded={isExpanded}
    >
        <svg
                width="12"
                height="12"
                viewBox="0 0 12 12"
                fill="none"
                xmlns="http://www.w3.org/2000/svg"
        >
            <path
                    d="M6 9L11 4H1L6 9Z"
                    fill="currentColor"
            />
        </svg>
    </button>

    {#if isExpanded}
        <div
                class="factories-container"
                transition:slide={{duration: 300, easing: cubicOut}}
        >
            <div class="search-container">
                <Search on:search={handleSearch} />
            </div>

            <div class="tabs">
                {#each tabs as tab}
                    <button
                            class="tab-button"
                            class:active={activeTab === tab}
                            onclick={() => activeTab = tab}
                    >
                        {tab}
                    </button>
                {/each}
            </div>

            <div class="factories-list">
                {#each componentFactories as factory (factory.name)}
                    <div class="theme-section">
                        <div class="theme-header">
                            <h3>{factory.name}</h3>
                            <button
                                    class="set-default-btn"
                                    onclick={() => setDefaultLayout(factory.name)}
                            >
                                Set as default
                            </button>
                        </div>
                        <div class="components-list">
                            {#each factory.components as component}
                                <div class="component-item">
                                    <span>{component}</span>
                                    <button
                                            class="add-btn"
                                            onclick={async () => await createComponent(factory.name, component)}
                                    >
                                        Add
                                    </button>
                                </div>
                            {/each}
                        </div>
                    </div>
                {/each}
            </div>
        </div>
    {/if}
</div>

<style lang="scss">
  @use "../../../colors" as *;

  .factories-wrapper {
    position: fixed;
    top: 0;
    left: 50%;
    transform: translateX(-50%);
    z-index: 1000;

    &.expanded {
      width: 500px;
    }
  }

  .expand-toggle {
    position: relative;
    left: 50%;
    transform: translateX(-50%);
    background-color: $accent-color;
    border: none;
    padding: 8px 16px;
    border-radius: 0 0 8px 8px;
    cursor: pointer;
    color: $hud-editor-manager-text-color;
    transition: all 0.3s ease;

    &.expanded svg {
      transform: rotate(180deg);
    }

    svg {
      transition: transform 0.3s ease;
    }
  }

  .factories-container {
    background-color: $hud-editor-manager-content-background-color;
    border-radius: 8px;
    margin-top: 8px;
    box-shadow: 0 4px 12px rgba(0, 0, 0, 0.2);
    display: flex;
    flex-direction: column;
    gap: 1rem;
    padding: 1rem;
    max-height: 60vh;
    overflow-y: auto;
  }

  .search-container {
    display: flex;
  }

  .tabs {
    display: flex;
    gap: 0.5rem;
    border-bottom: 1px solid $accent-color;
    padding-bottom: 0.5rem;

    .tab-button {
      background: none;
      border: none;
      color: $hud-editor-manager-text-color;
      padding: 0.5rem 1rem;
      cursor: pointer;
      border-radius: 4px;
      transition: background-color 0.2s;

      &:hover {
        background-color: rgba($accent-color, 0.1);
      }

      &.active {
        background-color: $accent-color;
      }
    }
  }

  .theme-section {
    margin-bottom: 1.5rem;

    &:last-child {
      margin-bottom: 0;
    }
  }

  .theme-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 0.5rem;

    h3 {
      color: $hud-editor-manager-text-color;
      margin: 0;
      font-size: 1rem;
    }

    .set-default-btn {
      background-color: rgba($accent-color, 0.2);
      color: $hud-editor-manager-text-color;
      border: 1px solid $accent-color;
      padding: 0.25rem 0.5rem;
      border-radius: 4px;
      cursor: pointer;
      font-size: 0.875rem;

      &:hover {
        background-color: rgba($accent-color, 0.3);
      }
    }
  }

  .components-list {
    display: flex;
    flex-direction: column;
    gap: 0.5rem;
  }

  .component-item {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 0.5rem;
    background-color: rgba($menu-base-color, 0.36);
    border-radius: 4px;

    span {
      color: $hud-editor-manager-text-color;
      font-family: "Inter", sans-serif;
    }

    .add-btn {
      background-color: $accent-color;
      color: $hud-editor-manager-text-color;
      border: none;
      padding: 0.25rem 0.5rem;
      border-radius: 4px;
      cursor: pointer;
      font-size: 0.875rem;
      font-family: "Inter", sans-serif;

      &:hover {
        background-color: darken($accent-color, 10%);
      }
    }
  }
</style>
