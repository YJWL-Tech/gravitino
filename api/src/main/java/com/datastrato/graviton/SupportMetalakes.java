package com.datastrato.graviton;

import com.datastrato.graviton.exceptions.MetalakeAlreadyExistsException;
import com.datastrato.graviton.exceptions.NoSuchMetalakeException;
import java.util.Map;

public interface SupportMetalakes {

  /** List all metalakes. */
  Metalake[] listMetalakes();

  /**
   * Load a metalake by its identifier.
   *
   * @param ident the identifier of the metalake.
   * @return The metalake.
   * @throws NoSuchMetalakeException If the metalake does not exist.
   */
  Metalake loadMetalake(NameIdentifier ident) throws NoSuchMetalakeException;

  /**
   * Check if a metalake exists.
   *
   * @param ident The identifier of the metalake.
   * @return True if the metalake exists, false otherwise.
   */
  default boolean metalakeExists(NameIdentifier ident) {
    try {
      loadMetalake(ident);
      return true;
    } catch (NoSuchMetalakeException e) {
      return false;
    }
  }

  /**
   * Create a metalake with specified identifier.
   *
   * @param ident The identifier of the metalake.
   * @param comment The comment of the metalake.
   * @param properties The properties of the metalake.
   * @return The created metalake.
   * @throws MetalakeAlreadyExistsException If the metalake already exists.
   */
  Metalake createMetalake(NameIdentifier ident, String comment, Map<String, String> properties)
      throws MetalakeAlreadyExistsException;

  /**
   * Alter a metalake with specified identifier.
   *
   * @param ident The identifier of the metalake.
   * @param changes The changes to apply.
   * @return The altered metalake.
   * @throws NoSuchMetalakeException If the metalake does not exist.
   * @throws IllegalArgumentException If the changes cannot be applied to the metalake.
   */
  Metalake alterMetalake(NameIdentifier ident, MetalakeChange... changes)
      throws NoSuchMetalakeException, IllegalArgumentException;

  /**
   * Drop a metalake with specified identifier.
   *
   * @param ident The identifier of the metalake.
   * @return True if the metalake was dropped, false otherwise.
   */
  boolean dropMetalake(NameIdentifier ident);
}
