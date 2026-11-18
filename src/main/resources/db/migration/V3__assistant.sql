-- Classement assisté : la catégorie enregistrée avec une opération vient soit d'un choix fait à la main
-- (MANUAL), soit de l'assistant (ASSISTANT : activité semblable déjà classée, ou proposition du LLM).
-- La colonne ne compte que si operation.category_id est renseigné.

ALTER TABLE operation ADD COLUMN category_source TEXT NOT NULL DEFAULT 'MANUAL'
    CHECK (category_source IN ('MANUAL', 'ASSISTANT'));
